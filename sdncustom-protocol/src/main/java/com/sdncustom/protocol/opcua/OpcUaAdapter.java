package com.sdncustom.protocol.opcua;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OPC-UA 协议适配器
 *
 * 地址格式: "ns=2;s=Temperature" 或 "ns=2;i=1001"
 */
@Slf4j
public class OpcUaAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpcUaClient client;
    private Channel channel;
    private boolean connected = false;

    private static final Map<String, OpcUaAdapter> instances = new ConcurrentHashMap<>();

    public static OpcUaAdapter getInstance(String channelId) {
        return instances.computeIfAbsent(channelId, k -> new OpcUaAdapter());
    }

    @Override
    public void connect(Channel channel) {
        this.channel = channel;
        try {
            Map<String, Object> config = objectMapper.readValue(channel.getConnectionConfig(), Map.class);
            String endpoint = (String) config.get("endpoint");
            // endpoint 格式: "opc.tcp://localhost:4840"

            client = OpcUaClient.create(endpoint);
            client.connect().get();
            connected = true;
            log.info("Connected to OPC-UA server: {}", endpoint);
        } catch (Exception e) {
            connected = false;
            log.error("Failed to connect to OPC-UA server: {}", e.getMessage());
            throw new RuntimeException("OPC-UA connection failed", e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        try {
            if (client != null) {
                client.disconnect().get();
            }
        } catch (Exception e) {
            log.error("Error disconnecting OPC-UA client", e);
        } finally {
            client = null;
            if (channel != null) {
                instances.remove(channel.getChannelId());
            }
        }
    }

    @Override
    public PointValue readPoint(MeasurementPoint point) {
        return readPoints(List.of(point)).stream().findFirst().orElse(null);
    }

    @Override
    public void writePoint(MeasurementPoint point, Object value) {
        if (!connected || client == null) {
            throw new RuntimeException("Not connected");
        }
        try {
            NodeId nodeId = parseNodeId(point.getAddress());
            Variant variant = convertToVariant(value, point.getDataType());

            StatusCode statusCode = client.writeValue(nodeId, new DataValue(variant, StatusCode.GOOD)).get();
            if (statusCode.isBad()) {
                throw new RuntimeException("OPC-UA write failed: " + statusCode);
            }
        } catch (Exception e) {
            log.error("Failed to write OPC-UA point: {}", point.getPointId(), e);
            throw new RuntimeException("OPC-UA write failed", e);
        }
    }

    @Override
    public List<PointValue> readPoints(List<MeasurementPoint> points) {
        if (!connected || client == null) {
            throw new RuntimeException("Not connected");
        }

        List<PointValue> results = new ArrayList<>();
        for (MeasurementPoint point : points) {
            try {
                PointValue pv = readSinglePoint(point);
                results.add(pv);
            } catch (Exception e) {
                log.error("Failed to read OPC-UA point: {}", point.getPointId(), e);
                PointValue pv = new PointValue();
                pv.setPointId(point.getPointId());
                pv.setQuality(PointQuality.BAD);
                pv.setTimestamp(System.currentTimeMillis());
                pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
                results.add(pv);
            }
        }
        return results;
    }

    @Override
    public boolean isConnected() {
        return connected && client != null;
    }

    private PointValue readSinglePoint(MeasurementPoint point) throws Exception {
        NodeId nodeId = parseNodeId(point.getAddress());

        DataValue dataValue = client.readValue(
                0.0,
                TimestampsToReturn.Both,
                nodeId
        ).get();

        PointValue pv = new PointValue();
        pv.setPointId(point.getPointId());
        pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);

        StatusCode statusCode = dataValue.getStatusCode();
        if (statusCode.isGood()) {
            Object value = dataValue.getValue().getValue();
            pv.setValue(convertValue(value, point.getDataType()));
            pv.setQuality(PointQuality.GOOD);
        } else if (statusCode.isUncertain()) {
            Object value = dataValue.getValue().getValue();
            pv.setValue(convertValue(value, point.getDataType()));
            pv.setQuality(PointQuality.UNCERTAIN);
        } else {
            pv.setValue(null);
            pv.setQuality(PointQuality.BAD);
        }

        pv.setTimestamp(dataValue.getSourceTime() != null ?
                dataValue.getSourceTime().getJavaTime() : System.currentTimeMillis());

        return pv;
    }

    private NodeId parseNodeId(String address) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("OPC-UA address cannot be empty");
        }

        // 解析格式: "ns=2;s=Temperature" 或 "ns=2;i=1001"
        String[] parts = address.split(";");
        int namespaceIndex = 0;
        String identifier = null;
        boolean isString = true;

        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("ns=")) {
                namespaceIndex = Integer.parseInt(part.substring(3));
            } else if (part.startsWith("s=")) {
                identifier = part.substring(2);
                isString = true;
            } else if (part.startsWith("i=")) {
                identifier = part.substring(2);
                isString = false;
            }
        }

        if (identifier == null) {
            throw new IllegalArgumentException("Invalid OPC-UA node ID: " + address);
        }

        if (isString) {
            return new NodeId(namespaceIndex, identifier);
        } else {
            return new NodeId(namespaceIndex, UInteger.valueOf(identifier));
        }
    }

    private Variant convertToVariant(Object value, PointDataType dataType) {
        if (value == null) {
            return new Variant(null);
        }
        switch (dataType) {
            case BOOL:
                return new Variant(value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value)));
            case INT16:
                return new Variant(value instanceof Number ? ((Number) value).shortValue() : Short.parseShort(String.valueOf(value)));
            case INT32:
                return new Variant(value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
            case FLOAT32:
                return new Variant(value instanceof Number ? ((Number) value).floatValue() : Float.parseFloat(String.valueOf(value)));
            case FLOAT64:
                return new Variant(value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value)));
            case STRING:
                return new Variant(String.valueOf(value));
            default:
                return new Variant(value);
        }
    }

    private Object convertValue(Object value, PointDataType dataType) {
        if (value == null) return null;
        try {
            switch (dataType) {
                case BOOL:
                    return value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value));
                case INT16:
                    return value instanceof Number ? ((Number) value).shortValue() : Short.parseShort(String.valueOf(value));
                case INT32:
                    return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
                case FLOAT32:
                    return value instanceof Number ? ((Number) value).floatValue() : Float.parseFloat(String.valueOf(value));
                case FLOAT64:
                    return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
                case STRING:
                    return String.valueOf(value);
                default:
                    return value;
            }
        } catch (Exception e) {
            log.warn("Failed to convert value: {} to type: {}", value, dataType, e);
            return value;
        }
    }
}
