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
    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;
    // 守护线程：调用方忘了 stop()（测试卡死、被中断）时也不会把 JVM 拖住不退出
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "mock-tcp-data");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, Object> pointValues = new ConcurrentHashMap<>();
    private final Random random = new Random();

    // 已接受的客户端连接，用于 stop() 时统一关闭，避免线程/连接泄漏
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();

    public MockTcpServer(int port) {
        this.port = port;
        initMockData();
    }

    /**
     * 初始化模拟测点数据，全部从 mock-data.json 加载。
     */
    private void initMockData() {
        String mockDataPath = findMockDataPath();
        if (mockDataPath != null) {
            loadFromMockData(mockDataPath);
        }
        if (pointValues.isEmpty()) {
            log.warn("No mock data loaded (mock-data.json not found or has no TCP points)");
        } else {
            log.info("Mock TCP data loaded: {} points", pointValues.size());
        }
    }

    /**
     * 从 mock-data.json 加载 TCP 通道的测点地址作为数据 key。
     */
    @SuppressWarnings("unchecked")
    public void loadFromMockData(String mockDataPath) {
        try {
            Map<String, Object> data = objectMapper.readValue(new File(mockDataPath), Map.class);
            List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("points");
            if (points == null) return;

            for (Map<String, Object> p : points) {
                String channelId = String.valueOf(p.getOrDefault("channelId", ""));
                if (!"ch_tcp_mock".equals(channelId)) continue;
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
     * 启动模拟服务器。
     *
     * <p>**同步 bind**：端口在这里就绑好，绑不上直接抛异常——原先在后台线程里 bind 且只
     * {@code log.error}，端口被占（例如上一次测试运行遗留的进程）时调用方毫不知情，
     * 客户端会连到别人的服务器上傻等。返回即代表端口已在监听，无需再 sleep 等待。
     *
     * @param port 监听端口；传 {@code 0} 表示由系统分配临时端口，之后用 {@link #getPort()} 取实际端口
     * @throws IllegalStateException 端口绑定失败
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new IllegalStateException("Mock TCP Server 绑定端口 " + port + " 失败"
                    + "（端口被占用？查一下是否残留着上一次运行的进程）: " + e.getMessage(), e);
        }
        running = true;
        log.info("Mock TCP Server started on port {}", getPort());

        Thread acceptThread = new Thread(() -> {
            try {
                while (running) {
                    Socket client = serverSocket.accept();
                    log.info("Client connected: {}", client.getRemoteSocketAddress());
                    Thread handler = new Thread(() -> handleClient(client));
                    handler.setDaemon(true);
                    handler.start();
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Mock TCP Server error", e);
                }
            }
        }, "mock-tcp-server");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // 定时更新模拟数据（模拟数据变化）
        scheduler.scheduleAtFixedRate(this::updateMockData, 1, 2, TimeUnit.SECONDS);
    }

    /** 实际监听端口（构造时传 0 则由系统分配，只在这里能拿到） */
    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket != null ? socket.getLocalPort() : port;
    }

    /**
     * 停止模拟服务器
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        // 关闭所有已接受的客户端连接，释放连接线程
        for (Socket client : clientSockets) {
            try {
                if (!client.isClosed()) {
                    client.close();
                }
            } catch (IOException e) {
                log.debug("Error closing client socket", e);
            }
        }
        clientSockets.clear();
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
        clientSockets.add(client);
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
            clientSockets.remove(client);
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
                List<String> addresses = (List<String>) req.get("addresses");
                if (addresses == null) {
                    addresses = pointIds != null ? pointIds : List.of();
                }
                if (pointIds == null) {
                    pointIds = addresses;
                }

                List<Map<String, Object>> values = new ArrayList<>();
                for (int i = 0; i < addresses.size(); i++) {
                    String address = addresses.get(i);
                    String pointId = i < pointIds.size() ? pointIds.get(i) : address;
                    Map<String, Object> v = new HashMap<>();
                    v.put("pointId", pointId);
                    v.put("value", pointValues.getOrDefault(address, 0));
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
                if (pointId != null && value != null) {
                    pointValues.put(pointId, value);
                }

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
     * 更新模拟数据（模拟实时变化）—— 动态迭代所有加载的条目，
     * 根据值类型施加随机波动。
     */
    private void updateMockData() {
        for (Map.Entry<String, Object> entry : pointValues.entrySet()) {
            Object current = entry.getValue();
            if (current instanceof Double d) {
                double amplitude = Math.max(Math.abs(d) * 0.02, 0.5);
                entry.setValue(d + (random.nextDouble() - 0.5) * amplitude * 2);
            } else if (current instanceof Integer i) {
                entry.setValue(i + random.nextInt(5) - 2);
            } else if (current instanceof Float f) {
                float amplitude = Math.max(Math.abs(f) * 0.02f, 0.5f);
                entry.setValue(f + (random.nextFloat() - 0.5f) * amplitude * 2);
            } else if (current instanceof Boolean) {
                entry.setValue(random.nextBoolean());
            }
            // String 节点不波动
        }
    }

    /**
     * 获取模拟数据（用于导出配置）
     */
    public Map<String, Object> getPointValues() {
        return Collections.unmodifiableMap(pointValues);
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9002;
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
