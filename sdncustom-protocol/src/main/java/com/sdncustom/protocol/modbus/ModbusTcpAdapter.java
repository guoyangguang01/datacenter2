package com.sdncustom.protocol.modbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modbus TCP 协议适配器
 *
 * 地址格式:
 * - 保持寄存器: 40001-49999 (功能码 0x03)
 * - 输入寄存器: 30001-39999 (功能码 0x04)
 * - 线圈: 00001-09999 (功能码 0x01)
 * - 离散输入: 10001-19999 (功能码 0x02)
 */
@Slf4j
public class ModbusTcpAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModbusTcpClient client = new ModbusTcpClient();
    private Channel channel;
    private String channelId;
    private boolean connected = false;

    private static final Map<String, ModbusTcpAdapter> instances = new ConcurrentHashMap<>();

    public static ModbusTcpAdapter getInstance(String channelId) {
        return instances.computeIfAbsent(channelId, k -> new ModbusTcpAdapter());
    }

    @Override
    public void connect(Channel channel) {
        this.channel = channel;
        this.channelId = channel.getChannelId();
        try {
            Map<String, Object> config = objectMapper.readValue(channel.getConnectionConfig(), Map.class);
            String host = (String) config.get("host");
            int port = config.containsKey("port") ? (int) config.get("port") : 502;
            int unitId = config.containsKey("unitId") ? (int) config.get("unitId") : 1;

            client.setUnitId(unitId);
            client.connect(host, port);
            connected = true;
            log.info("Connected to Modbus TCP server: {}:{}", host, port);
        } catch (Exception e) {
            connected = false;
            // 清理可能已创建的连接资源
            client.disconnect();
            // 从实例缓存中移除
            if (channelId != null) {
                instances.remove(channelId);
            }
            log.error("Failed to connect to Modbus TCP server: {}", e.getMessage());
            throw new RuntimeException("Modbus connection failed", e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        client.disconnect();
        if (channelId != null) {
            instances.remove(channelId);
        }
    }

    @Override
    public PointValue readPoint(MeasurementPoint point) {
        return readPoints(List.of(point)).stream().findFirst().orElse(null);
    }

    @Override
    public void writePoint(MeasurementPoint point, Object value) {
        if (!connected) {
            throw new RuntimeException("Not connected");
        }
        try {
            ModbusAddress addr = parseAddress(point.getAddress());
            int intValue = convertToInt(value, point.getDataType());

            if (addr.type == AddressType.HOLDING_REGISTER) {
                client.writeSingleRegister(addr.registerAddress, intValue);
            } else if (addr.type == AddressType.COIL) {
                client.writeSingleCoil(addr.registerAddress, intValue != 0);
            } else {
                throw new RuntimeException("Cannot write to address type: " + addr.type);
            }
        } catch (Exception e) {
            log.error("Failed to write Modbus point: {}", point.getPointId(), e);
            throw new RuntimeException("Modbus write failed", e);
        }
    }

    @Override
    public List<PointValue> readPoints(List<MeasurementPoint> points) {
        if (!connected) {
            throw new RuntimeException("Not connected");
        }

        List<PointValue> results = new ArrayList<>();
        for (MeasurementPoint point : points) {
            try {
                PointValue pv = readSinglePoint(point);
                if (pv != null) {
                    results.add(pv);
                }
            } catch (Exception e) {
                log.error("Failed to read Modbus point: {}", point.getPointId(), e);
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
        return connected && client.isConnected();
    }

    private PointValue readSinglePoint(MeasurementPoint point) throws Exception {
        ModbusAddress addr = parseAddress(point.getAddress());
        Object value;
        PointQuality quality = PointQuality.GOOD;

        switch (addr.type) {
            case HOLDING_REGISTER: {
                int[] regs = client.readHoldingRegisters(addr.registerAddress, 1);
                value = regs[0];
                break;
            }
            case INPUT_REGISTER: {
                int[] regs = client.readInputRegisters(addr.registerAddress, 1);
                value = regs[0];
                break;
            }
            case COIL: {
                boolean[] coils = client.readCoils(addr.registerAddress, 1);
                value = coils[0];
                break;
            }
            default:
                throw new RuntimeException("Unsupported address type: " + addr.type);
        }

        // 转换数据类型
        value = convertValue(value, point.getDataType());

        PointValue pv = new PointValue();
        pv.setPointId(point.getPointId());
        pv.setValue(value);
        pv.setQuality(quality);
        pv.setTimestamp(System.currentTimeMillis());
        pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
        return pv;
    }

    private ModbusAddress parseAddress(String address) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be empty");
        }

        // 标准 Modbus 地址格式: 40001, 30001, 00001, 10001
        int addr;
        try {
            addr = Integer.parseInt(address);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Modbus address: " + address);
        }

        if (addr >= 40001 && addr <= 49999) {
            return new ModbusAddress(AddressType.HOLDING_REGISTER, addr - 40001);
        } else if (addr >= 30001 && addr <= 39999) {
            return new ModbusAddress(AddressType.INPUT_REGISTER, addr - 30001);
        } else if (addr >= 1 && addr <= 9999) {
            return new ModbusAddress(AddressType.COIL, addr - 1);
        } else if (addr >= 10001 && addr <= 19999) {
            return new ModbusAddress(AddressType.DISCRETE_INPUT, addr - 10001);
        } else {
            // 直接地址，假设为保持寄存器
            return new ModbusAddress(AddressType.HOLDING_REGISTER, addr);
        }
    }

    private Object convertValue(Object value, PointDataType dataType) {
        if (value == null) return null;
        double numValue = value instanceof Number ? ((Number) value).doubleValue() : 0;
        switch (dataType) {
            case BOOL:
                return value instanceof Boolean ? value : numValue != 0;
            case INT16:
                return (short) numValue;
            case INT32:
                return (int) numValue;
            case FLOAT32:
                return (float) numValue;
            case FLOAT64:
                return numValue;
            case STRING:
                return String.valueOf(value);
            default:
                return value;
        }
    }

    private int convertToInt(Object value, PointDataType dataType) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private enum AddressType {
        HOLDING_REGISTER,
        INPUT_REGISTER,
        COIL,
        DISCRETE_INPUT
    }

    private static class ModbusAddress {
        final AddressType type;
        final int registerAddress;

        ModbusAddress(AddressType type, int registerAddress) {
            this.type = type;
            this.registerAddress = registerAddress;
        }
    }
}
