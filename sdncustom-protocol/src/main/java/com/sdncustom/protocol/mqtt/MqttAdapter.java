package com.sdncustom.protocol.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT 协议适配器
 *
 * 地址格式: "sensors/temperature" (MQTT Topic)
 *
 * 连接配置:
 * - broker: "tcp://localhost:1883"
 * - clientId: "sdncustom_client"
 * - username: (可选)
 * - password: (可选)
 */
@Slf4j
public class MqttAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MqttClient client;
    private Channel channel;
    private boolean connected = false;

    // 缓存最新的测点值 (topic -> value)
    private final Map<String, PointValue> valueCache = new ConcurrentHashMap<>();

    private static final Map<String, MqttAdapter> instances = new ConcurrentHashMap<>();

    public static MqttAdapter getInstance(String channelId) {
        return instances.computeIfAbsent(channelId, k -> new MqttAdapter());
    }

    @Override
    public void connect(Channel channel) {
        this.channel = channel;
        try {
            Map<String, Object> config = objectMapper.readValue(channel.getConnectionConfig(), Map.class);
            String broker = (String) config.get("broker");
            String clientId = config.containsKey("clientId") ? (String) config.get("clientId") : "sdncustom_" + System.currentTimeMillis();
            String username = config.containsKey("username") ? (String) config.get("username") : null;
            String password = config.containsKey("password") ? (String) config.get("password") : null;

            client = new MqttClient(broker, clientId, new MemoryPersistence());

            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setCleanStart(true);
            options.setAutomaticReconnect(false);
            if (username != null) {
                options.setUserName(username);
            }
            if (password != null) {
                options.setPassword(password.getBytes(StandardCharsets.UTF_8));
            }

            client.setCallback(new MqttCallback() {
                @Override
                public void disconnected(MqttDisconnectResponse response) {
                    connected = false;
                    log.warn("MQTT disconnected: {}", response.getReasonString());
                }

                @Override
                public void mqttErrorOccurred(MqttException exception) {
                    log.error("MQTT error", exception);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleMessage(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttToken token) {
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT connected to: {}", serverURI);
                }

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {
                }
            });

            client.connect(options);
            connected = true;
            log.info("Connected to MQTT broker: {}", broker);
        } catch (Exception e) {
            connected = false;
            log.error("Failed to connect to MQTT broker: {}", e.getMessage());
            throw new RuntimeException("MQTT connection failed", e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            log.error("Error disconnecting MQTT client", e);
        } finally {
            client = null;
            valueCache.clear();
            if (channel != null) {
                instances.remove(channel.getChannelId());
            }
        }
    }

    @Override
    public PointValue readPoint(MeasurementPoint point) {
        return valueCache.get(point.getAddress());
    }

    @Override
    public void writePoint(MeasurementPoint point, Object value) {
        if (!connected || client == null) {
            throw new RuntimeException("Not connected");
        }
        try {
            String topic = point.getAddress();
            String payload = objectMapper.writeValueAsString(value);
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            client.publish(topic, message);
            log.debug("Published to {}: {}", topic, payload);
        } catch (Exception e) {
            log.error("Failed to publish MQTT message: {}", point.getPointId(), e);
            throw new RuntimeException("MQTT publish failed", e);
        }
    }

    @Override
    public List<PointValue> readPoints(List<MeasurementPoint> points) {
        List<PointValue> results = new ArrayList<>();
        for (MeasurementPoint point : points) {
            PointValue pv = valueCache.get(point.getAddress());
            if (pv == null) {
                pv = new PointValue();
                pv.setPointId(point.getPointId());
                pv.setValue(null);
                pv.setQuality(PointQuality.COMM_LOST);
                pv.setTimestamp(System.currentTimeMillis());
                pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
            }
            results.add(pv);
        }
        return results;
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    /**
     * 订阅 MQTT Topic
     */
    public void subscribe(String topic) {
        if (!connected || client == null) {
            throw new RuntimeException("Not connected");
        }
        try {
            client.subscribe(topic, 1);
            log.info("Subscribed to MQTT topic: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to topic: {}", topic, e);
            throw new RuntimeException("MQTT subscribe failed", e);
        }
    }

    /**
     * 批量订阅
     */
    public void subscribeAll(List<String> topics) {
        for (String topic : topics) {
            subscribe(topic);
        }
    }

    private void handleMessage(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            Object value = parsePayload(payload);

            PointValue pv = new PointValue();
            pv.setPointId(topic); // 使用 topic 作为 pointId
            pv.setValue(value);
            pv.setQuality(PointQuality.GOOD);
            pv.setTimestamp(System.currentTimeMillis());
            pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);

            valueCache.put(topic, pv);
            log.debug("Received message from {}: {}", topic, payload);
        } catch (Exception e) {
            log.error("Failed to handle MQTT message from topic: {}", topic, e);
        }
    }

    private Object parsePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        // 尝试解析为 JSON
        try {
            return objectMapper.readValue(payload, Object.class);
        } catch (Exception e) {
            // 如果不是 JSON，返回原始字符串
            return payload;
        }
    }
}
