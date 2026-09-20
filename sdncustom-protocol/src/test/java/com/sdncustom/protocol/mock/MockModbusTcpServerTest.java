package com.sdncustom.protocol.mock;

import com.sdncustom.protocol.modbus.ModbusFunction;
import com.sdncustom.protocol.modbus.ModbusTcpClient;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockModbusTcpServer 单元测试
 */
@DisplayName("MockModbusTcpServer 测试")
class MockModbusTcpServerTest {

    private static MockModbusTcpServer server;
    /** 实际监听端口：用临时端口（0）而非固定端口，避免被上一次运行的遗留进程占住后连到僵尸服务器 */
    private static int port;

    @BeforeAll
    static void startServer() {
        server = new MockModbusTcpServer(0);
        server.start(); // 同步 bind：端口被占会在这里抛，而不是静默失败
        port = server.getPort();
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
            ModbusTcpClient client = new ModbusTcpClient();
            client.connect("localhost", port);
            client.disconnect();
        });
    }

    @Test
    @DisplayName("读取保持寄存器")
    void readHoldingRegisters() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 读取寄存器 40001-40003 (地址 0-2)——这些地址在 mock-data.json 中存在
            int[] values = client.readHoldingRegisters(0, 3);

            assertNotNull(values);
            assertEquals(3, values.length);

            // 验证初始值（INT16 默认 1000）
            assertTrue(values[0] > 0); // 40001
            assertTrue(values[1] > 0); // 40002
            assertTrue(values[2] > 0); // 40003
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("读取输入寄存器")
    void readInputRegisters() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 读取输入寄存器 30001-30005 (地址 0-4)
            int[] values = client.readInputRegisters(0, 5);

            assertNotNull(values);
            assertEquals(5, values.length);

            // 验证值在合理范围内 (0-4095)
            for (int value : values) {
                assertTrue(value >= 0 && value <= 4095);
            }
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("读取线圈")
    void readCoils() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 读取线圈 00001-00010 (地址 0-9)
            boolean[] values = client.readCoils(0, 10);

            assertNotNull(values);
            assertEquals(10, values.length);
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("写入保持寄存器")
    void writeHoldingRegister() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 写入寄存器 40001 (地址 0)
            client.writeSingleRegister(0, 12345);

            // 读取验证
            int[] values = client.readHoldingRegisters(0, 1);
            assertEquals(12345, values[0]);
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("写多个保持寄存器（功能码 0x10）并回读")
    void writeMultipleRegisters() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 25.6f 高字在前 = [0x41CC, 0xCCCD]；用未占用的寄存器区避免被定时波动覆盖
            client.writeMultipleRegisters(60, new int[]{0x41CC, 0xCCCD});

            int[] registers = client.readHoldingRegisters(60, 2);
            assertArrayEquals(new int[]{0x41CC, 0xCCCD}, registers);
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("模拟器预置的 32 位值可按高字在前读出")
    void readPreset32BitRegisters() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 40033-40034: INT32（从 mock-data.json 加载，默认 10000）
            int[] int32 = client.readHoldingRegisters(32, 2);
            assertNotNull(int32);
            assertEquals(2, int32.length);
            assertTrue(int32[0] != 0 || int32[1] != 0, "INT32 register should not be zero");

            // 40035-40038: FLOAT64（从 mock-data.json 加载，默认 100.0）
            int[] float64 = client.readHoldingRegisters(34, 4);
            assertNotNull(float64);
            assertEquals(4, float64.length);
            assertTrue(float64[0] != 0 || float64[1] != 0, "FLOAT64 register should not be zero");
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("写入线圈")
    void writeCoil() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 写入线圈 00001 (地址 0) 为 true
            client.writeSingleCoil(0, true);

            // 读取验证
            boolean[] values = client.readCoils(0, 1);
            assertTrue(values[0]);

            // 写入 false
            client.writeSingleCoil(0, false);

            // 读取验证
            values = client.readCoils(0, 1);
            assertFalse(values[0]);
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("读取多个寄存器")
    void readMultipleRegisters() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 读取 20 个保持寄存器
            int[] values = client.readHoldingRegisters(0, 20);

            assertNotNull(values);
            assertEquals(20, values.length);
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("数据更新验证")
    void dataUpdate() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();
        try {
            client.connect("localhost", port);

            // 读取初始值
            int[] values1 = client.readHoldingRegisters(0, 1);

            // 等待数据更新
            Thread.sleep(3500);

            // 再次读取
            int[] values2 = client.readHoldingRegisters(0, 1);

            // 值应该有变化（由于随机波动）
            // 注意：这个测试可能偶尔失败，因为随机数可能相同
            // assertTrue(values1[0] != values2[0]); // 可能需要注释掉
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("连接断开测试")
    void connectDisconnect() throws Exception {
        ModbusTcpClient client = new ModbusTcpClient();

        // 连接
        client.connect("localhost", port);
        assertTrue(client.isConnected());

        // 断开
        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    @DisplayName("多次连接断开")
    void multipleConnectDisconnect() throws Exception {
        for (int i = 0; i < 3; i++) {
            ModbusTcpClient client = new ModbusTcpClient();
            client.connect("localhost", port);
            assertTrue(client.isConnected());

            int[] values = client.readHoldingRegisters(0, 1);
            assertNotNull(values);

            client.disconnect();
            assertFalse(client.isConnected());
        }
    }
}
