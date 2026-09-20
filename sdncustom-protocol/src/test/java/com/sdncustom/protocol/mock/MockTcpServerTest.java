package com.sdncustom.protocol.mock;

import com.sdncustom.protocol.tcp.TcpCommand;
import com.sdncustom.protocol.tcp.TcpMessage;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockTcpServer 单元测试
 *
 * <p>三条防挂死措施（原实现三条全缺，导致一次被中断的运行会让后续每次运行永久阻塞、
 * 且无法中断——详见 docs/backlog.md「协议 mock 测试」一节）：
 * <ol>
 *   <li><b>临时端口</b>（构造传 {@code 0}）：固定端口一旦被上次运行的遗留进程占着，
 *       客户端就会连到那个僵尸服务器上傻等</li>
 *   <li><b>客户端读超时</b>：没有 {@code setSoTimeout}，对端不应答就是永久阻塞</li>
 *   <li><b>不吞失败</b>：原先读响应处的 {@code if (lengthBytes.length < 4) return;}
 *       会把「服务器根本没响应」伪装成通过；这里一律断言</li>
 * </ol>
 */
@DisplayName("MockTcpServer 测试")
class MockTcpServerTest {

    private static final int READ_TIMEOUT_MS = 5000;

    private static MockTcpServer server;
    private static int port;

    @BeforeAll
    static void startServer() {
        server = new MockTcpServer(0); // 0 = 由系统分配空闲端口
        server.start();                // 同步 bind：端口被占会在这里抛，而不是静默失败
        port = server.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** 一帧响应：命令码 + body */
    private record Frame(byte command, String body) {
    }

    /** 建连接并设读超时——超时让「对端不应答」变成失败，而不是挂住整个测试运行 */
    private static Socket openClient() throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return socket;
    }

    private static void send(DataOutputStream out, byte command, String body) throws IOException {
        out.write(new TcpMessage(command, body).encode());
        out.flush();
    }

    /** 读一帧；长度头/响应体不完整即断言失败（旧实现在这里静默 return） */
    private static Frame readFrame(DataInputStream in) throws IOException {
        byte[] lengthBytes = in.readNBytes(4);
        assertEquals(4, lengthBytes.length, "响应长度头不完整：服务器没有应答（或连到了别的进程）");

        int length = ((lengthBytes[0] & 0xFF) << 24) |
                     ((lengthBytes[1] & 0xFF) << 16) |
                     ((lengthBytes[2] & 0xFF) << 8) |
                     (lengthBytes[3] & 0xFF);

        byte[] data = in.readNBytes(length);
        assertEquals(length, data.length, "响应体不完整");
        assertTrue(length >= 1, "响应帧至少要有命令码");
        return new Frame(data[0], new String(data, 1, length - 1, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("服务器启动成功且监听真实端口")
    void serverStarted() {
        assertTrue(port > 0, "未绑定到实际端口");
        assertDoesNotThrow(() -> {
            try (Socket socket = openClient()) {
                assertTrue(socket.isConnected());
            }
        });
    }

    @Test
    @DisplayName("端口被占用：start() 抛异常并点名端口，而不是静默失败")
    void portConflictFailsLoudly() throws IOException {
        // 这条是本次挂死事故的回归测试：旧实现在后台线程里 bind、失败只 log.error，
        // 调用方以为服务器起来了，客户端于是连到占用端口的那个进程上傻等
        try (java.net.ServerSocket blocker = new java.net.ServerSocket(0)) {
            int busyPort = blocker.getLocalPort();
            MockTcpServer conflicting = new MockTcpServer(busyPort);
            try {
                IllegalStateException e = assertThrows(IllegalStateException.class, conflicting::start);
                assertTrue(e.getMessage().contains(String.valueOf(busyPort)),
                        "报错应点名端口，实际: " + e.getMessage());
            } finally {
                conflicting.stop();
            }
        }
    }

    @Test
    @DisplayName("读取测点值")
    void readPoints() throws Exception {
        try (Socket socket = openClient();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            send(output, TcpCommand.READ_REQUEST, "{\"pointIds\":[\"int16_001\",\"float32_001\",\"bool_001\"]}");
            Frame response = readFrame(input);

            assertEquals(TcpCommand.READ_RESPONSE, response.command());
            assertTrue(response.body().contains("values"), "响应缺少 values: " + response.body());
        }
    }

    @Test
    @DisplayName("写入测点值")
    void writePoint() throws Exception {
        try (Socket socket = openClient();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            send(output, TcpCommand.WRITE_REQUEST, "{\"pointId\":\"int16_001\",\"value\":12345}");
            Frame response = readFrame(input);

            assertEquals(TcpCommand.WRITE_RESPONSE, response.command());
            assertTrue(response.body().contains("success"), "响应缺少 success: " + response.body());
        }
    }

    @Test
    @DisplayName("心跳请求响应")
    void heartbeat() throws Exception {
        try (Socket socket = openClient();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            send(output, TcpCommand.HEARTBEAT_REQUEST, "");
            Frame response = readFrame(input);

            assertEquals(TcpCommand.HEARTBEAT_RESPONSE, response.command());
            assertTrue(response.body().contains("ok"), "响应缺少 status: " + response.body());
        }
    }

    @Test
    @DisplayName("写入后读取验证")
    void writeThenRead() throws Exception {
        try (Socket socket = openClient();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 写成字符串：MockTcpServer 每 2s 会对数值型做随机波动，字符串不波动，
            // 这样"读回来的就是我刚写的"能被确定性断言（旧用例只断言了 body 非空，等于没验证）
            send(output, TcpCommand.WRITE_REQUEST, "{\"pointId\":\"test_write_point\",\"value\":\"WRITTEN-9999\"}");
            assertEquals(TcpCommand.WRITE_RESPONSE, readFrame(input).command());

            send(output, TcpCommand.READ_REQUEST, "{\"pointIds\":[\"test_write_point\"]}");
            Frame response = readFrame(input);

            assertEquals(TcpCommand.READ_RESPONSE, response.command());
            assertTrue(response.body().contains("WRITTEN-9999"),
                    "读回的值不是刚写入的: " + response.body());
        }
    }

    @Test
    @DisplayName("获取模拟数据")
    void getPointValues() {
        Map<String, Object> values = server.getPointValues();

        assertNotNull(values);
        assertFalse(values.isEmpty());

        // 验证各种数据类型
        assertTrue(values.containsKey("bool_001"));
        assertTrue(values.containsKey("int16_001"));
        assertTrue(values.containsKey("int32_001"));
        assertTrue(values.containsKey("float32_001"));
        assertTrue(values.containsKey("float64_001"));
        assertTrue(values.containsKey("string_001"));
    }

    @Test
    @DisplayName("数据类型验证")
    void dataTypes() {
        Map<String, Object> values = server.getPointValues();

        // BOOL
        assertInstanceOf(Boolean.class, values.get("bool_001"));

        // INT16
        assertInstanceOf(Integer.class, values.get("int16_001"));

        // INT32
        assertInstanceOf(Integer.class, values.get("int32_001"));

        // FLOAT32
        assertInstanceOf(Float.class, values.get("float32_001"));

        // FLOAT64
        assertInstanceOf(Double.class, values.get("float64_001"));

        // STRING
        assertInstanceOf(String.class, values.get("string_001"));
    }
}
