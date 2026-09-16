package com.sdncustom.protocol.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * MQTT 模拟客户端
 * 定期向指定 Topic 发布各类型测点数据
 */
@Slf4j
public class MockMqttClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final String broker;
    private final String clientId;
    private final String username;
    private final String password;
    private MqttClient client;
    private volatile boolean running = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Random random = new Random();

    // 模拟测点配置: topic -> 当前值
    private final Map<String, Object> pointValues = new LinkedHashMap<>();

    public MockMqttClient(String broker, String clientId) {
        this(broker, clientId, null, null);
    }

    public MockMqttClient(String broker, String clientId, String username, String password) {
        this.broker = broker;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        initMockData();
    }

    /**
     * 初始化模拟数据，全部从 mock-data.json 加载。
     */
    private void initMockData() {
        String mockDataPath = findMockDataPath();
        if (mockDataPath != null) {
            loadFromMockData(mockDataPath);
        }
        if (pointValues.isEmpty()) {
            log.warn("No MQTT mock data loaded (mock-data.json not found or has no MQTT points)");
        } else {
            log.info("Mock MQTT data loaded: {} topics", pointValues.size());
        }
    }

    /**
     * 从 mock-data.json 加载 MQTT 通道的测点地址（topic 路径）作为数据 key。
     */
    @SuppressWarnings("unchecked")
    public void loadFromMockData(String mockDataPath) {
        try {
            Map<String, Object> data = objectMapper.readValue(new File(mockDataPath), Map.class);
            List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("points");
            if (points == null) return;

            for (Map<String, Object> p : points) {
                String channelId = String.valueOf(p.getOrDefault("channelId", ""));
                if (!"ch_mqtt_mock".equals(channelId)) continue;
                String address = String.valueOf(p.getOrDefault("address", ""));
                String dataType = String.valueOf(p.getOrDefault("dataType", "FLOAT64"));
                if (address.isEmpty()) continue;
                if (pointValues.containsKey(address)) continue;
                pointValues.put(address, defaultValueFor(dataType));
            }
        } catch (IOException e) {
            log.warn("Failed to load mock-data.json: {}", e.getMessage());
        }
    }

    private static Object defaultValueFor(String dataType) {
        return switch (dataType) {
            case "BOOL" -> false;
            case "INT16" -> 1000;
            case "INT32" -> 10000;
            case "FLOAT32" -> 25.0f;
            case "FLOAT64" -> 100.0;
            case "STRING" -> "OK";
            default -> 0;
        };
    }

    /** 从 mock/ 目录或当前目录向上查找 mock-data.json */
    static String findMockDataPath() {
        String[] candidates = {"mock/mock-data.json", "../mock/mock-data.json", "mock-data.json"};
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
    }

    /**
     * 启动模拟客户端
     */
    public void start() {
        try {
            // 使用内存持久化，避免创建 .lck 文件
            client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectionOptions options = new MqttConnectionOptions();
            options.setCleanStart(true);
            options.setAutomaticReconnect(true);

            // 设置认证信息
            if (username != null && !username.isEmpty()) {
                options.setUserName(username);
                if (password != null) {
                    options.setPassword(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            client.connect(options);
            running = true;
            log.info("Mock MQTT Client connected to {}", broker);

            // 定时发布数据
            scheduler.scheduleAtFixedRate(this::publishAllData, 1, 2, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Failed to connect Mock MQTT Client", e);
        }
    }

    /**
     * 停止模拟客户端
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
                client.close();
            }
        } catch (Exception e) {
            log.error("Error disconnecting Mock MQTT Client", e);
        }
        log.info("Mock MQTT Client stopped");
    }

    /**
     * 发布所有测点数据
     */
    private void publishAllData() {
        if (!running || client == null || !client.isConnected()) return;

        updateMockData();

        for (Map.Entry<String, Object> entry : pointValues.entrySet()) {
            try {
                String topic = entry.getKey();
                Object value = entry.getValue();
                String payload;

                if (value instanceof String) {
                    payload = (String) value;
                } else {
                    payload = objectMapper.writeValueAsString(value);
                }

                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(1);
                client.publish(topic, message);

            } catch (Exception e) {
                log.error("Failed to publish MQTT message", e);
            }
        }
    }

    /**
     * 更新模拟数据 —— 动态迭代从 mock-data.json 加载的所有条目，
     * 根据值类型施加随机波动。
     */
    private void updateMockData() {
        for (Map.Entry<String, Object> entry : pointValues.entrySet()) {
            Object current = entry.getValue();
            if (current instanceof Double d) {
                double amplitude = Math.max(Math.abs(d) * 0.02, 0.5);
                entry.setValue(round(d + (random.nextDouble() - 0.5) * amplitude * 2, 1));
            } else if (current instanceof Integer i) {
                entry.setValue(i + random.nextInt(5) - 2);
            } else if (current instanceof Float f) {
                float amplitude = Math.max(Math.abs(f) * 0.02f, 0.5f);
                entry.setValue(round(f + (random.nextFloat() - 0.5f) * amplitude * 2, 1));
            } else if (current instanceof Boolean) {
                entry.setValue(random.nextBoolean());
            }
            // String 节点不波动
        }
    }

    private double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    public static void main(String[] args) {
        String broker = args.length > 0 ? args[0] : "tcp://localhost:1883";
        String username = args.length > 1 ? args[1] : null;
        String password = args.length > 2 ? args[2] : null;
        String clientId = "sdncustom_mock_" + System.currentTimeMillis();

        MockMqttClient mockClient = new MockMqttClient(broker, clientId, username, password);
        mockClient.start();

        Runtime.getRuntime().addShutdownHook(new Thread(mockClient::stop));
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {
        }
    }
}
