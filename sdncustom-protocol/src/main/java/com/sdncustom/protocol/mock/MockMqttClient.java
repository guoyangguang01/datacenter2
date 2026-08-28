package com.sdncustom.protocol.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.MqttMessage;

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
     * 初始化模拟数据
     */
    private void initMockData() {
        // 温度传感器 (10个)
        pointValues.put("sensors/temperature/room1", 25.6);
        pointValues.put("sensors/temperature/room2", 23.4);
        pointValues.put("sensors/temperature/outdoor", 30.1);
        pointValues.put("sensors/temperature/warehouse", 18.5);
        pointValues.put("sensors/temperature/coldroom", -5.2);
        pointValues.put("sensors/temperature/boiler", 85.3);
        pointValues.put("sensors/temperature/chiller", 7.8);
        pointValues.put("sensors/temperature/ambient", 28.4);
        pointValues.put("sensors/temperature/inlet", 45.6);
        pointValues.put("sensors/temperature/outlet", 67.8);

        // 湿度传感器 (6个)
        pointValues.put("sensors/humidity/room1", 65.2);
        pointValues.put("sensors/humidity/room2", 58.7);
        pointValues.put("sensors/humidity/warehouse", 45.3);
        pointValues.put("sensors/humidity/outdoor", 72.1);
        pointValues.put("sensors/humidity/coldroom", 85.6);
        pointValues.put("sensors/humidity/cleanroom", 42.8);

        // 压力传感器 (8个)
        pointValues.put("sensors/pressure/pipeline1", 101.3);
        pointValues.put("sensors/pressure/pipeline2", 202.6);
        pointValues.put("sensors/pressure/steam", 350.5);
        pointValues.put("sensors/pressure/hydraulic", 1500.0);
        pointValues.put("sensors/pressure/pneumatic", 600.0);
        pointValues.put("sensors/pressure/vacuum", -95.0);
        pointValues.put("sensors/pressure/tank1", 150.2);
        pointValues.put("sensors/pressure/tank2", 180.8);

        // 流量传感器 (4个)
        pointValues.put("sensors/flow/mainpipe", 50.0);
        pointValues.put("sensors/flow/branch1", 20.0);
        pointValues.put("sensors/flow/branch2", 15.0);
        pointValues.put("sensors/flow/return", 45.0);

        // 液位传感器 (4个)
        pointValues.put("sensors/level/tank1", 75.5);
        pointValues.put("sensors/level/tank2", 45.2);
        pointValues.put("sensors/level/tank3", 88.9);
        pointValues.put("sensors/level/sump", 32.1);

        // 泵状态 (6个)
        pointValues.put("devices/pump01/status", "running");
        pointValues.put("devices/pump01/speed", 1500);
        pointValues.put("devices/pump01/current", 5.2);
        pointValues.put("devices/pump02/status", "stopped");
        pointValues.put("devices/pump02/speed", 0);
        pointValues.put("devices/pump02/current", 0.0);
        pointValues.put("devices/pump03/status", "running");
        pointValues.put("devices/pump03/speed", 1200);
        pointValues.put("devices/pump03/current", 4.8);
        pointValues.put("devices/pump04/status", "standby");
        pointValues.put("devices/pump04/speed", 0);
        pointValues.put("devices/pump04/current", 0.1);

        // 阀门状态 (8个)
        pointValues.put("valves/valve01/state", true);
        pointValues.put("valves/valve01/position", 100.0);
        pointValues.put("valves/valve02/state", false);
        pointValues.put("valves/valve02/position", 0.0);
        pointValues.put("valves/valve03/state", true);
        pointValues.put("valves/valve03/position", 50.0);
        pointValues.put("valves/valve04/state", false);
        pointValues.put("valves/valve04/position", 0.0);

        // 风机状态 (4个)
        pointValues.put("devices/fan01/status", "running");
        pointValues.put("devices/fan01/speed", 2800);
        pointValues.put("devices/fan02/status", "stopped");
        pointValues.put("devices/fan02/speed", 0);

        // 电表数据 (3组)
        pointValues.put("meters/emeter01/voltage", 220.5);
        pointValues.put("meters/emeter01/current", 5.2);
        pointValues.put("meters/emeter01/power", 1145.1);
        pointValues.put("meters/emeter01/energy", 12345.6);
        pointValues.put("meters/emeter02/voltage", 380.0);
        pointValues.put("meters/emeter02/current", 12.5);
        pointValues.put("meters/emeter02/power", 4750.0);
        pointValues.put("meters/emeter02/energy", 56789.0);

        // 计数器 (6个)
        pointValues.put("counters/production/total", 12345);
        pointValues.put("counters/production/defects", 23);
        pointValues.put("counters/production/productA", 8000);
        pointValues.put("counters/production/productB", 4345);
        pointValues.put("counters/production/rework", 156);
        pointValues.put("counters/production/scrap", 45);

        // 环境监测 (4个)
        pointValues.put("environment/co2", 450.0);
        pointValues.put("environment/pm25", 35.0);
        pointValues.put("environment/noise", 65.0);
        pointValues.put("environment/light", 500.0);

        // 告警信息 (3个)
        pointValues.put("alarms/latest", "Normal");
        pointValues.put("alarms/critical", "None");
        pointValues.put("alarms/warning", "None");

        // 系统状态 (4个)
        pointValues.put("system/status", "Running");
        pointValues.put("system/uptime", 86400);
        pointValues.put("system/cpu", 45.2);
        pointValues.put("system/memory", 62.8);
    }

    /**
     * 启动模拟客户端
     */
    public void start() {
        try {
            client = new MqttClient(broker, clientId);
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
     * 更新模拟数据
     */
    private void updateMockData() {
        // 温度波动 (10个)
        pointValues.put("sensors/temperature/room1", round(25.6 + (random.nextDouble() - 0.5) * 4, 1));
        pointValues.put("sensors/temperature/room2", round(23.4 + (random.nextDouble() - 0.5) * 3, 1));
        pointValues.put("sensors/temperature/outdoor", round(30.1 + (random.nextDouble() - 0.5) * 6, 1));
        pointValues.put("sensors/temperature/warehouse", round(18.5 + (random.nextDouble() - 0.5) * 4, 1));
        pointValues.put("sensors/temperature/coldroom", round(-5.2 + (random.nextDouble() - 0.5) * 3, 1));
        pointValues.put("sensors/temperature/boiler", round(85.3 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/temperature/chiller", round(7.8 + (random.nextDouble() - 0.5) * 4, 1));
        pointValues.put("sensors/temperature/ambient", round(28.4 + (random.nextDouble() - 0.5) * 5, 1));
        pointValues.put("sensors/temperature/inlet", round(45.6 + (random.nextDouble() - 0.5) * 6, 1));
        pointValues.put("sensors/temperature/outlet", round(67.8 + (random.nextDouble() - 0.5) * 8, 1));

        // 湿度波动 (6个)
        pointValues.put("sensors/humidity/room1", round(65.2 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/humidity/room2", round(58.7 + (random.nextDouble() - 0.5) * 8, 1));
        pointValues.put("sensors/humidity/warehouse", round(45.3 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/humidity/outdoor", round(72.1 + (random.nextDouble() - 0.5) * 15, 1));
        pointValues.put("sensors/humidity/coldroom", round(85.6 + (random.nextDouble() - 0.5) * 5, 1));
        pointValues.put("sensors/humidity/cleanroom", round(42.8 + (random.nextDouble() - 0.5) * 3, 1));

        // 压力波动 (8个)
        pointValues.put("sensors/pressure/pipeline1", round(101.3 + (random.nextDouble() - 0.5) * 5, 1));
        pointValues.put("sensors/pressure/pipeline2", round(202.6 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/pressure/steam", round(350.5 + (random.nextDouble() - 0.5) * 20, 1));
        pointValues.put("sensors/pressure/hydraulic", round(1500.0 + (random.nextDouble() - 0.5) * 100, 1));
        pointValues.put("sensors/pressure/pneumatic", round(600.0 + (random.nextDouble() - 0.5) * 30, 1));
        pointValues.put("sensors/pressure/vacuum", round(-95.0 + (random.nextDouble() - 0.5) * 5, 1));
        pointValues.put("sensors/pressure/tank1", round(150.2 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/pressure/tank2", round(180.8 + (random.nextDouble() - 0.5) * 12, 1));

        // 流量波动 (4个)
        pointValues.put("sensors/flow/mainpipe", round(50.0 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/flow/branch1", round(20.0 + (random.nextDouble() - 0.5) * 5, 1));
        pointValues.put("sensors/flow/branch2", round(15.0 + (random.nextDouble() - 0.5) * 4, 1));
        pointValues.put("sensors/flow/return", round(45.0 + (random.nextDouble() - 0.5) * 8, 1));

        // 液位波动 (4个)
        pointValues.put("sensors/level/tank1", round(75.5 + (random.nextDouble() - 0.5) * 10, 1));
        pointValues.put("sensors/level/tank2", round(45.2 + (random.nextDouble() - 0.5) * 8, 1));
        pointValues.put("sensors/level/tank3", round(88.9 + (random.nextDouble() - 0.5) * 6, 1));
        pointValues.put("sensors/level/sump", round(32.1 + (random.nextDouble() - 0.5) * 10, 1));

        // 泵状态 (6个)
        boolean pump1Running = random.nextDouble() > 0.1;
        pointValues.put("devices/pump01/status", pump1Running ? "running" : "stopped");
        pointValues.put("devices/pump01/speed", pump1Running ? 1500 + random.nextInt(200) - 100 : 0);
        pointValues.put("devices/pump01/current", pump1Running ? round(5.0 + (random.nextDouble() - 0.5) * 2, 2) : 0.0);

        boolean pump2Running = random.nextDouble() > 0.7;
        pointValues.put("devices/pump02/status", pump2Running ? "running" : "stopped");
        pointValues.put("devices/pump02/speed", pump2Running ? 1200 + random.nextInt(150) - 75 : 0);
        pointValues.put("devices/pump02/current", pump2Running ? round(4.5 + (random.nextDouble() - 0.5) * 1.5, 2) : 0.0);

        boolean pump3Running = random.nextDouble() > 0.2;
        pointValues.put("devices/pump03/status", pump3Running ? "running" : "stopped");
        pointValues.put("devices/pump03/speed", pump3Running ? 1200 + random.nextInt(100) - 50 : 0);
        pointValues.put("devices/pump03/current", pump3Running ? round(4.8 + (random.nextDouble() - 0.5) * 1.8, 2) : 0.0);

        pointValues.put("devices/pump04/status", "standby");

        // 阀门状态 (8个)
        boolean v1 = random.nextBoolean();
        pointValues.put("valves/valve01/state", v1);
        pointValues.put("valves/valve01/position", v1 ? 100.0 : 0.0);

        boolean v2 = random.nextBoolean();
        pointValues.put("valves/valve02/state", v2);
        pointValues.put("valves/valve02/position", v2 ? 100.0 : 0.0);

        double v3pos = round(50.0 + (random.nextDouble() - 0.5) * 40, 1);
        pointValues.put("valves/valve03/state", v3pos > 10);
        pointValues.put("valves/valve03/position", v3pos);

        pointValues.put("valves/valve04/state", false);
        pointValues.put("valves/valve04/position", 0.0);

        // 风机状态 (4个)
        boolean fan1Running = random.nextDouble() > 0.15;
        pointValues.put("devices/fan01/status", fan1Running ? "running" : "stopped");
        pointValues.put("devices/fan01/speed", fan1Running ? 2800 + random.nextInt(400) - 200 : 0);

        pointValues.put("devices/fan02/status", random.nextDouble() > 0.6 ? "running" : "stopped");
        pointValues.put("devices/fan02/speed", (double) pointValues.get("devices/fan02/speed"));

        // 电表数据 (3组)
        double v1m = round(220.0 + (random.nextDouble() - 0.5) * 10, 1);
        double c1m = round(5.0 + (random.nextDouble() - 0.5) * 2, 2);
        pointValues.put("meters/emeter01/voltage", v1m);
        pointValues.put("meters/emeter01/current", c1m);
        pointValues.put("meters/emeter01/power", round(v1m * c1m, 1));
        pointValues.put("meters/emeter01/energy", round((double) pointValues.get("meters/emeter01/energy") + random.nextDouble() * 5, 1));

        double v2m = round(380.0 + (random.nextDouble() - 0.5) * 15, 1);
        double c2m = round(12.0 + (random.nextDouble() - 0.5) * 4, 2);
        pointValues.put("meters/emeter02/voltage", v2m);
        pointValues.put("meters/emeter02/current", c2m);
        pointValues.put("meters/emeter02/power", round(v2m * c2m, 1));
        pointValues.put("meters/emeter02/energy", round((double) pointValues.get("meters/emeter02/energy") + random.nextDouble() * 20, 1));

        // 计数器递增 (6个)
        pointValues.put("counters/production/total", (int) pointValues.get("counters/production/total") + random.nextInt(5));
        if (random.nextDouble() < 0.1) {
            pointValues.put("counters/production/defects", (int) pointValues.get("counters/production/defects") + 1);
        }
        pointValues.put("counters/production/productA", (int) pointValues.get("counters/production/productA") + random.nextInt(3));
        pointValues.put("counters/production/productB", (int) pointValues.get("counters/production/productB") + random.nextInt(2));
        if (random.nextDouble() < 0.05) {
            pointValues.put("counters/production/rework", (int) pointValues.get("counters/production/rework") + 1);
        }
        if (random.nextDouble() < 0.02) {
            pointValues.put("counters/production/scrap", (int) pointValues.get("counters/production/scrap") + 1);
        }

        // 环境监测 (4个)
        pointValues.put("environment/co2", round(450.0 + (random.nextDouble() - 0.5) * 100, 1));
        pointValues.put("environment/pm25", round(35.0 + (random.nextDouble() - 0.5) * 20, 1));
        pointValues.put("environment/noise", round(65.0 + (random.nextDouble() - 0.5) * 15, 1));
        pointValues.put("environment/light", round(500.0 + (random.nextDouble() - 0.5) * 200, 1));

        // 告警随机切换 (3个)
        String[] alarms = {"Normal", "High Temperature", "Low Pressure", "Overload", "Communication Error", "Sensor Fault"};
        pointValues.put("alarms/latest", random.nextDouble() < 0.8 ? "Normal" : alarms[random.nextInt(alarms.length)]);
        pointValues.put("alarms/critical", random.nextDouble() < 0.95 ? "None" : "Critical: " + alarms[random.nextInt(alarms.length)]);
        pointValues.put("alarms/warning", random.nextDouble() < 0.85 ? "None" : "Warning: " + alarms[random.nextInt(alarms.length)]);

        // 系统状态 (4个)
        String[] sysStatus = {"Running", "Idle", "Maintenance", "Error"};
        pointValues.put("system/status", random.nextDouble() < 0.9 ? "Running" : sysStatus[random.nextInt(sysStatus.length)]);
        pointValues.put("system/uptime", (int) pointValues.get("system/uptime") + 2);
        pointValues.put("system/cpu", round(45.0 + (random.nextDouble() - 0.5) * 30, 1));
        pointValues.put("system/memory", round(62.0 + (random.nextDouble() - 0.5) * 20, 1));
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
