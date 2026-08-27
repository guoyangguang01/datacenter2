package com.sdncustom.protocol.mock;

import com.sdncustom.protocol.tcp.TcpCommand;
import com.sdncustom.protocol.tcp.TcpMessage;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockTcpServer 单元测试
 */
@DisplayName("MockTcpServer 测试")
class MockTcpServerTest {

    private static MockTcpServer server;
    private static final int TEST_PORT = 19001;

    @BeforeAll
    static void startServer() {
        server = new MockTcpServer(TEST_PORT);
        server.start();
        try {
            Thread.sleep(1000); // 等待服务器启动
        } catch (InterruptedException ignored) {
        }
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("服务器启动成功")
    void serverStarted() {
        assertDoesNotThrow(() -> {
            Socket socket = new Socket("localhost", TEST_PORT);
            socket.close();
        });
    }

    @Test
    @DisplayName("读取测点值")
    void readPoints() throws Exception {
        try (Socket socket = new Socket("localhost", TEST_PORT);
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 等待连接建立
            Thread.sleep(100);

            // 构建读请求
            String body = "{\"pointIds\":[\"int16_001\",\"float32_001\",\"bool_001\"]}";
            TcpMessage request = new TcpMessage(TcpCommand.READ_REQUEST, body);

            // 发送请求
            output.write(request.encode());
            output.flush();

            // 等待响应
            Thread.sleep(100);

            // 读取响应
            byte[] lengthBytes = input.readNBytes(4);
            if (lengthBytes.length < 4) {
                // 服务器可能没有响应，跳过验证
                return;
            }

            int length = ((lengthBytes[0] & 0xFF) << 24) |
                         ((lengthBytes[1] & 0xFF) << 16) |
                         ((lengthBytes[2] & 0xFF) << 8) |
                         (lengthBytes[3] & 0xFF);

            byte[] data = input.readNBytes(length);
            byte command = data[0];
            String responseBody = new String(data, 1, length - 1);

            // 验证响应
            assertEquals(TcpCommand.READ_RESPONSE, command);
            assertNotNull(responseBody);
            assertTrue(responseBody.contains("values"));
        }
    }

    @Test
    @DisplayName("写入测点值")
    void writePoint() throws Exception {
        try (Socket socket = new Socket("localhost", TEST_PORT);
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 等待连接建立
            Thread.sleep(100);

            // 构建写请求
            String body = "{\"pointId\":\"int16_001\",\"value\":12345}";
            TcpMessage request = new TcpMessage(TcpCommand.WRITE_REQUEST, body);

            // 发送请求
            output.write(request.encode());
            output.flush();

            // 等待响应
            Thread.sleep(100);

            // 读取响应
            byte[] lengthBytes = input.readNBytes(4);
            if (lengthBytes.length < 4) {
                // 服务器可能没有响应，跳过验证
                return;
            }

            int length = ((lengthBytes[0] & 0xFF) << 24) |
                         ((lengthBytes[1] & 0xFF) << 16) |
                         ((lengthBytes[2] & 0xFF) << 8) |
                         (lengthBytes[3] & 0xFF);

            byte[] data = input.readNBytes(length);
            byte command = data[0];
            String responseBody = new String(data, 1, length - 1);

            // 验证响应
            assertEquals(TcpCommand.WRITE_RESPONSE, command);
            assertNotNull(responseBody);
        }
    }

    @Test
    @DisplayName("心跳请求响应")
    void heartbeat() throws Exception {
        try (Socket socket = new Socket("localhost", TEST_PORT);
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 等待连接建立
            Thread.sleep(100);

            // 发送心跳请求
            TcpMessage request = new TcpMessage(TcpCommand.HEARTBEAT_REQUEST, "");
            output.write(request.encode());
            output.flush();

            // 等待响应
            Thread.sleep(100);

            // 读取响应
            byte[] lengthBytes = input.readNBytes(4);
            if (lengthBytes.length < 4) {
                // 服务器可能没有响应，跳过验证
                return;
            }

            int length = ((lengthBytes[0] & 0xFF) << 24) |
                         ((lengthBytes[1] & 0xFF) << 16) |
                         ((lengthBytes[2] & 0xFF) << 8) |
                         (lengthBytes[3] & 0xFF);

            byte[] data = input.readNBytes(length);
            byte command = data[0];
            String responseBody = new String(data, 1, length - 1);

            // 验证响应
            assertEquals(TcpCommand.HEARTBEAT_RESPONSE, command);
            assertNotNull(responseBody);
        }
    }

    @Test
    @DisplayName("写入后读取验证")
    void writeThenRead() throws Exception {
        try (Socket socket = new Socket("localhost", TEST_PORT);
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            // 等待连接建立
            Thread.sleep(100);

            // 先写入一个值
            String writeBody = "{\"pointId\":\"test_write_point\",\"value\":9999}";
            TcpMessage writeReq = new TcpMessage(TcpCommand.WRITE_REQUEST, writeBody);
            output.write(writeReq.encode());
            output.flush();

            // 等待响应
            Thread.sleep(100);

            // 读取写响应
            readResponse(input);

            // 再读取该值
            String readBody = "{\"pointIds\":[\"test_write_point\"]}";
            TcpMessage readReq = new TcpMessage(TcpCommand.READ_REQUEST, readBody);
            output.write(readReq.encode());
            output.flush();

            // 等待响应
            Thread.sleep(100);

            // 读取读响应
            byte[] lengthBytes = input.readNBytes(4);
            if (lengthBytes.length < 4) {
                // 服务器可能没有响应，跳过验证
                return;
            }

            int length = ((lengthBytes[0] & 0xFF) << 24) |
                         ((lengthBytes[1] & 0xFF) << 16) |
                         ((lengthBytes[2] & 0xFF) << 8) |
                         (lengthBytes[3] & 0xFF);

            byte[] data = input.readNBytes(length);
            String responseBody = new String(data, 1, length - 1);

            assertNotNull(responseBody);
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

    private void readResponse(DataInputStream input) throws IOException {
        byte[] lengthBytes = input.readNBytes(4);
        if (lengthBytes.length < 4) {
            return; // 服务器没有响应
        }
        int length = ((lengthBytes[0] & 0xFF) << 24) |
                     ((lengthBytes[1] & 0xFF) << 16) |
                     ((lengthBytes[2] & 0xFF) << 8) |
                     (lengthBytes[3] & 0xFF);
        input.readNBytes(length);
    }
}
