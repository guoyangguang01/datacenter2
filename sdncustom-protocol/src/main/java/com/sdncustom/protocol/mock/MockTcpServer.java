package com.sdncustom.protocol.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.protocol.tcp.TcpCommand;
import com.sdncustom.protocol.tcp.TcpMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * 自定义 TCP 协议模拟服务器
 * 支持读写请求、心跳，模拟各类型测点数据
 */
@Slf4j
public class MockTcpServer {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, Object> pointValues = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public MockTcpServer(int port) {
        this.port = port;
        initMockData();
    }

    /**
     * 初始化模拟测点数据
     */
    private void initMockData() {
        // BOOL 类型 - 设备状态
        pointValues.put("bool_001", true);   // 设备1运行
        pointValues.put("bool_002", false);  // 设备2停止
        pointValues.put("bool_003", true);   // 设备3运行
        pointValues.put("bool_004", false);  // 报警信号
        pointValues.put("bool_005", true);   // 联锁状态
        pointValues.put("bool_006", false);  // 维护模式
        pointValues.put("bool_007", true);   // 自动模式
        pointValues.put("bool_008", false);  // 手动模式
        pointValues.put("bool_009", true);   // 门禁状态
        pointValues.put("bool_010", false);  // 消防报警

        // INT16 类型 - 模拟量设定值
        pointValues.put("int16_001", 1234);   // 温度设定值
        pointValues.put("int16_002", -5678);  // 压力设定值
        pointValues.put("int16_003", 0);      // 流量设定值
        pointValues.put("int16_004", 500);    // 液位设定值
        pointValues.put("int16_005", 1500);   // 转速设定值
        pointValues.put("int16_006", 380);    // 电压设定值
        pointValues.put("int16_007", 50);     // 频率设定值
        pointValues.put("int16_008", 100);    // 功率设定值
        pointValues.put("int16_009", 75);     // 湿度设定值
        pointValues.put("int16_010", 200);    // 液压设定值

        // INT32 类型 - 计数器和累计值
        pointValues.put("int32_001", 123456);   // 生产计数
        pointValues.put("int32_002", -789012);  // 偏移量
        pointValues.put("int32_003", 999999);   // 累计脉冲
        pointValues.put("int32_004", 456789);   // 运行时间(秒)
        pointValues.put("int32_005", 7890);     // 故障次数
        pointValues.put("int32_006", 12345);    // 维护计数
        pointValues.put("int32_007", 67890);    // 产品A计数
        pointValues.put("int32_008", 23456);    // 产品B计数
        pointValues.put("int32_009", 89012);    // 合格品计数
        pointValues.put("int32_010", 34567);    // 不合格品计数

        // FLOAT32 类型 - 过程变量
        pointValues.put("float32_001", 25.6f);    // 环境温度
        pointValues.put("float32_002", -10.5f);   // 低温区温度
        pointValues.put("float32_003", 100.0f);   // 高温区温度
        pointValues.put("float32_004", 45.3f);    // 进口温度
        pointValues.put("float32_005", 67.8f);    // 出口温度
        pointValues.put("float32_006", 101.3f);   // 大气压力
        pointValues.put("float32_007", 202.6f);   // 管道压力
        pointValues.put("float32_008", 50.0f);    // 主管流量
        pointValues.put("float32_009", 75.5f);    // 储罐液位
        pointValues.put("float32_010", 88.2f);    // 湿度

        // FLOAT64 类型 - 高精度测量值
        pointValues.put("float64_001", 3.14159265);   // 精确测量值1
        pointValues.put("float64_002", -2.71828182);  // 精确测量值2
        pointValues.put("float64_003", 1024.512);     // 精确测量值3
        pointValues.put("float64_004", 0.001234);     // 微小变化量
        pointValues.put("float64_005", 99999.999);    // 大数值
        pointValues.put("float64_006", 220.5);        // 电压有效值
        pointValues.put("float64_007", 5.23);         // 电流有效值
        pointValues.put("float64_008", 1145.1);       // 有功功率
        pointValues.put("float64_009", 0.85);         // 功率因数
        pointValues.put("float64_010", 50.01);        // 频率

        // STRING 类型 - 状态信息
        pointValues.put("string_001", "Hello IoT");      // 设备标识
        pointValues.put("string_002", "Running");         // 运行状态
        pointValues.put("string_003", "Normal");          // 告警状态
        pointValues.put("string_004", "Auto");            // 运行模式
        pointValues.put("string_005", "Product A");       // 当前产品
        pointValues.put("string_006", "Batch-2024-001");  // 批次号
        pointValues.put("string_007", "OK");              // 质量状态
        pointValues.put("string_008", "Zone-1");          // 当前区域
        pointValues.put("string_009", "Shift-A");         // 当前班次
        pointValues.put("string_010", "Online");          // 通信状态
    }

    /**
     * 启动模拟服务器
     */
    public void start() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                log.info("Mock TCP Server started on port {}", port);
                while (running) {
                    Socket client = serverSocket.accept();
                    log.info("Client connected: {}", client.getRemoteSocketAddress());
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Mock TCP Server error", e);
                }
            }
        }, "mock-tcp-server").start();

        // 定时更新模拟数据（模拟数据变化）
        scheduler.scheduleAtFixedRate(this::updateMockData, 1, 2, TimeUnit.SECONDS);
    }

    /**
     * 停止模拟服务器
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("Error closing mock server", e);
        }
        log.info("Mock TCP Server stopped");
    }

    /**
     * 处理客户端连接
     */
    private void handleClient(Socket client) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(client.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()))) {

            client.setSoTimeout(30000);

            while (running && !client.isClosed()) {
                try {
                    // 读取长度 (4 bytes)
                    byte[] lengthBytes = input.readNBytes(4);
                    if (lengthBytes.length < 4) break;

                    int length = ((lengthBytes[0] & 0xFF) << 24) |
                                 ((lengthBytes[1] & 0xFF) << 16) |
                                 ((lengthBytes[2] & 0xFF) << 8) |
                                 (lengthBytes[3] & 0xFF);

                    // 读取 command + body
                    byte[] data = input.readNBytes(length);
                    if (data.length < length) break;

                    byte command = data[0];
                    String body = length > 1 ? new String(data, 1, length - 1) : "";

                    TcpMessage request = new TcpMessage(command, body);
                    TcpMessage response = processRequest(request);

                    // 发送响应
                    byte[] responseData = response.encode();
                    output.write(responseData);
                    output.flush();

                } catch (java.net.SocketTimeoutException e) {
                    // 超时，继续等待
                } catch (IOException e) {
                    log.debug("Client disconnected: {}", e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            log.error("Error handling client", e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
            log.info("Client connection closed");
        }
    }

    /**
     * 处理请求并生成响应
     */
    @SuppressWarnings("unchecked")
    private TcpMessage processRequest(TcpMessage request) throws IOException {
        switch (request.getCommand()) {
            case TcpCommand.READ_REQUEST: {
                Map<String, Object> req = objectMapper.readValue(request.getBody(), Map.class);
                List<String> pointIds = (List<String>) req.get("pointIds");

                List<Map<String, Object>> values = new ArrayList<>();
                for (String pointId : pointIds) {
                    Map<String, Object> v = new HashMap<>();
                    v.put("pointId", pointId);
                    v.put("value", pointValues.getOrDefault(pointId, 0));
                    v.put("quality", "GOOD");
                    v.put("timestamp", System.currentTimeMillis());
                    values.add(v);
                }

                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("values", values);
                return new TcpMessage(TcpCommand.READ_RESPONSE, objectMapper.writeValueAsString(responseBody));
            }

            case TcpCommand.WRITE_REQUEST: {
                Map<String, Object> req = objectMapper.readValue(request.getBody(), Map.class);
                String pointId = (String) req.get("pointId");
                Object value = req.get("value");
                pointValues.put(pointId, value);

                Map<String, Object> responseBody = new HashMap<>();
                responseBody.put("success", true);
                return new TcpMessage(TcpCommand.WRITE_RESPONSE, objectMapper.writeValueAsString(responseBody));
            }

            case TcpCommand.HEARTBEAT_REQUEST: {
                return new TcpMessage(TcpCommand.HEARTBEAT_RESPONSE, "{\"status\":\"ok\"}");
            }

            default:
                return new TcpMessage((byte) 0xFF, "{\"error\":\"unknown command\"}");
        }
    }

    /**
     * 更新模拟数据（模拟实时变化）
     */
    private void updateMockData() {
        // BOOL 随机切换
        pointValues.put("bool_001", random.nextBoolean());
        pointValues.put("bool_002", random.nextBoolean());
        pointValues.put("bool_003", random.nextBoolean());
        pointValues.put("bool_004", random.nextDouble() < 0.1);  // 10% 概率报警
        pointValues.put("bool_005", random.nextBoolean());
        pointValues.put("bool_009", random.nextBoolean());

        // INT16 小幅波动
        pointValues.put("int16_001", (int) pointValues.get("int16_001") + random.nextInt(11) - 5);
        pointValues.put("int16_003", random.nextInt(100));
        pointValues.put("int16_004", Math.max(0, Math.min(1000, (int) pointValues.get("int16_004") + random.nextInt(21) - 10)));
        pointValues.put("int16_005", Math.max(0, Math.min(3000, (int) pointValues.get("int16_005") + random.nextInt(101) - 50)));
        pointValues.put("int16_009", Math.max(0, Math.min(100, (int) pointValues.get("int16_009") + random.nextInt(11) - 5)));

        // INT32 递增
        pointValues.put("int32_001", (int) pointValues.get("int32_001") + random.nextInt(101) - 50);
        pointValues.put("int32_004", (int) pointValues.get("int32_004") + 2);  // 运行时间递增
        pointValues.put("int32_007", (int) pointValues.get("int32_007") + random.nextInt(5));
        pointValues.put("int32_008", (int) pointValues.get("int32_008") + random.nextInt(3));
        pointValues.put("int32_009", (int) pointValues.get("int32_009") + random.nextInt(4));
        if (random.nextDouble() < 0.05) { // 5% 概率产生不合格品
            pointValues.put("int32_010", (int) pointValues.get("int32_010") + 1);
        }

        // FLOAT32 波动
        pointValues.put("float32_001", (float) pointValues.get("float32_001") + (random.nextFloat() - 0.5f) * 2);
        pointValues.put("float32_002", (float) pointValues.get("float32_002") + (random.nextFloat() - 0.5f));
        pointValues.put("float32_003", (float) pointValues.get("float32_003") + (random.nextFloat() - 0.5f) * 3);
        pointValues.put("float32_004", (float) pointValues.get("float32_004") + (random.nextFloat() - 0.5f) * 2);
        pointValues.put("float32_005", (float) pointValues.get("float32_005") + (random.nextFloat() - 0.5f) * 2);
        pointValues.put("float32_006", (float) pointValues.get("float32_006") + (random.nextFloat() - 0.5f) * 5);
        pointValues.put("float32_007", (float) pointValues.get("float32_007") + (random.nextFloat() - 0.5f) * 10);
        pointValues.put("float32_008", (float) pointValues.get("float32_008") + (random.nextFloat() - 0.5f) * 5);
        pointValues.put("float32_009", (float) pointValues.get("float32_009") + (random.nextFloat() - 0.5f) * 4);
        pointValues.put("float32_010", (float) pointValues.get("float32_010") + (random.nextFloat() - 0.5f) * 5);

        // FLOAT64 波动
        pointValues.put("float64_001", (double) pointValues.get("float64_001") + (random.nextDouble() - 0.5) * 0.1);
        pointValues.put("float64_003", (double) pointValues.get("float64_003") + random.nextDouble() * 10 - 5);
        pointValues.put("float64_006", 220.0 + (random.nextDouble() - 0.5) * 10);  // 电压波动
        pointValues.put("float64_007", 5.0 + (random.nextDouble() - 0.5) * 2);      // 电流波动
        pointValues.put("float64_008", (double) pointValues.get("float64_006") * (double) pointValues.get("float64_007"));  // 功率计算
        pointValues.put("float64_009", 0.85 + (random.nextDouble() - 0.5) * 0.1);   // 功率因数波动
        pointValues.put("float64_010", 50.0 + (random.nextDouble() - 0.5) * 0.1);   // 频率波动

        // STRING 随机切换状态
        String[] runStatuses = {"Running", "Idle", "Warning", "Normal", "High Load", "Starting", "Stopping"};
        pointValues.put("string_002", runStatuses[random.nextInt(runStatuses.length)]);

        String[] alarms = {"Normal", "High Temperature", "Low Pressure", "Overload", "Communication Error", "Sensor Fault"};
        pointValues.put("string_003", random.nextDouble() < 0.8 ? "Normal" : alarms[random.nextInt(alarms.length)]);

        String[] modes = {"Auto", "Manual", "Maintenance", "Setup"};
        pointValues.put("string_004", modes[random.nextInt(modes.length)]);

        String[] products = {"Product A", "Product B", "Product C", "Product D"};
        pointValues.put("string_005", products[random.nextInt(products.length)]);

        String[] quality = {"OK", "NG", "Pending", "Rework"};
        pointValues.put("string_007", random.nextDouble() < 0.9 ? "OK" : quality[random.nextInt(quality.length)]);

        String[] zones = {"Zone-1", "Zone-2", "Zone-3", "Zone-4", "Zone-5"};
        pointValues.put("string_008", zones[random.nextInt(zones.length)]);

        String[] shifts = {"Shift-A", "Shift-B", "Shift-C"};
        pointValues.put("string_009", shifts[random.nextInt(shifts.length)]);

        String[] commStatus = {"Online", "Offline", "Timeout", "Error"};
        pointValues.put("string_010", random.nextDouble() < 0.9 ? "Online" : commStatus[random.nextInt(commStatus.length)]);
    }

    /**
     * 获取模拟数据（用于导出配置）
     */
    public Map<String, Object> getPointValues() {
        return Collections.unmodifiableMap(pointValues);
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        MockTcpServer server = new MockTcpServer(port);
        server.start();

        // 保持运行
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {
        }
    }
}
