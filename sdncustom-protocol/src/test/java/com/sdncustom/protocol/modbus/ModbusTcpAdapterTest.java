package com.sdncustom.protocol.modbus;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.mock.MockModbusTcpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModbusTcpAdapter 适配器层测试：对接 MockModbusTcpServer 走真实 TCP，
 * 覆盖地址解析、字序、读写路由、故障语义（读失败主动断开消除"假连接"）。
 *
 * 寄存器约定：只使用 40036 以上的保持寄存器与 10 号之后的线圈——
 * mock-data.json 中 ch_modbus_mock 的地址（≤40035、线圈 1-7）会被
 * 服务器每 3s 随机波动，避开它们保证断言确定性。
 */
@DisplayName("ModbusTcpAdapter 适配器测试")
class ModbusTcpAdapterTest {

    private static MockModbusTcpServer server;
    private static int port;

    /** 当前测试持有的适配器，@AfterEach 统一断开 */
    private ModbusTcpAdapter adapter;

    @BeforeAll
    static void startServer() {
        server = new MockModbusTcpServer(0);
        server.start();
        port = server.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.disconnect();
            adapter = null;
        }
    }

    // ---------- 连接生命周期 ----------

    @Test
    @DisplayName("连接成功后 isConnected 为 true，断开后为 false")
    void connectAndDisconnect() {
        adapter = newAdapter(cfgFull("big"));
        assertTrue(adapter.isConnected());
        adapter.disconnect();
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("连接失败抛异常且保持未连接状态（含连接资源清理）")
    void connectFailureKeepsDisconnected() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        ModbusTcpAdapter a = new ModbusTcpAdapter();
        Channel ch = channel("{\"host\":\"localhost\",\"port\":" + freePort + "}");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> a.connect(ch));
        assertEquals("Modbus connection failed", ex.getMessage());
        assertFalse(a.isConnected());
    }

    @Test
    @DisplayName("配置省略 unitId/wordOrder 时按默认值 1 / big 处理")
    void defaultsWhenConfigOmitsOptionalKeys() throws Exception {
        ModbusTcpAdapter a = newAdapter("{\"host\":\"localhost\",\"port\":" + port + "}");
        try {
            assertTrue(a.isConnected());
            // 默认 big：写 FLOAT32 落线上应为高字在前
            a.writePoint(point("p_def", "40071", PointDataType.FLOAT32), 25.6f);
            try (RawClient raw = rawClient()) {
                assertArrayEquals(new int[]{0x41CC, 0xCCCD}, raw.client.readHoldingRegisters(70, 2));
            }
        } finally {
            a.disconnect();
        }
    }

    // ---------- 读路径 ----------

    @Test
    @DisplayName("读保持寄存器 INT16：值、质量、来源通道正确")
    void readInt16HoldingRegister() throws Exception {
        try (RawClient raw = rawClient()) {
            raw.client.writeSingleRegister(60, 1234);
        }
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_i16", "40061", PointDataType.INT16));
        assertEquals("p_i16", pv.getPointId());
        assertEquals((short) 1234, pv.getValue());
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals("ch_test", pv.getSourceChannelId());
        assertTrue(pv.getTimestamp() > 0);
    }

    @Test
    @DisplayName("读 INT32：高字在前的两个寄存器解码为一个整数")
    void readInt32BigWordOrder() throws Exception {
        try (RawClient raw = rawClient()) {
            raw.client.writeMultipleRegisters(61, ModbusRegisters.encode(-123456, PointDataType.INT32, false));
        }
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_i32", "40062", PointDataType.INT32));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals(-123456, ((Number) pv.getValue()).intValue());
    }

    @Test
    @DisplayName("读 FLOAT64：占 4 个连续寄存器")
    void readFloat64() throws Exception {
        try (RawClient raw = rawClient()) {
            raw.client.writeMultipleRegisters(65, ModbusRegisters.encode(3.14159265, PointDataType.FLOAT64, false));
        }
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_f64", "40066", PointDataType.FLOAT64));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals(3.14159265, ((Number) pv.getValue()).doubleValue(), 1e-12);
    }

    @Test
    @DisplayName("读输入寄存器 30001（服务器侧恒为 0）")
    void readInputRegister() {
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_in", "30001", PointDataType.INT16));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals((short) 0, pv.getValue());
    }

    @Test
    @DisplayName("读线圈 10：写 true 后读回 true")
    void readCoil() throws Exception {
        try (RawClient raw = rawClient()) {
            raw.client.writeSingleCoil(9, true);
        }
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_coil", "10", PointDataType.BOOL));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals(Boolean.TRUE, pv.getValue());
    }

    @Test
    @DisplayName("读 STRING：单寄存器按字符串返回")
    void readStringRegister() throws Exception {
        try (RawClient raw = rawClient()) {
            raw.client.writeSingleRegister(78, 1234);
        }
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_str", "40079", PointDataType.STRING));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertEquals("1234", pv.getValue());
    }

    @Test
    @DisplayName("读离散输入：服务器不支持 0x02 → BAD 并主动断开（消除假连接）")
    void readDiscreteInputFailsAndDisconnects() {
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_disc", "10001", PointDataType.BOOL));
        assertEquals(PointQuality.BAD, pv.getQuality());
        assertFalse(adapter.isConnected(), "读失败后必须主动关闭连接，让 isConnected 如实反映");
    }

    @Test
    @DisplayName("未连接时 readPoint 返回 COMM_LOST，readPoints 抛 Not connected")
    void readWhenNotConnected() {
        ModbusTcpAdapter a = new ModbusTcpAdapter();
        PointValue pv = a.readPoint(point("p_lc", "40061", PointDataType.INT16));
        assertEquals(PointQuality.COMM_LOST, pv.getQuality());
        assertNull(pv.getValue());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> a.readPoints(List.of(point("p_lc2", "40061", PointDataType.INT16))));
        assertEquals("Not connected", ex.getMessage());
    }

    @Test
    @DisplayName("服务器消失后的迟到读取：BAD + 主动断开（假连接修复路径）")
    void readAfterServerShutdownMarksBadAndDisconnects() {
        MockModbusTcpServer localServer = new MockModbusTcpServer(0);
        localServer.start();
        ModbusTcpAdapter a = new ModbusTcpAdapter();
        try {
            a.connect(channel("{\"host\":\"localhost\",\"port\":" + localServer.getPort() + "}"));
            assertTrue(a.isConnected());

            localServer.stop();

            PointValue pv = a.readPoint(point("p_dead", "40061", PointDataType.INT16));
            assertEquals(PointQuality.BAD, pv.getQuality());
            assertFalse(a.isConnected());
        } finally {
            a.disconnect();
            localServer.stop();
        }
    }

    @Test
    @DisplayName("批量读取：多个正常测点全部返回 GOOD")
    void readPointsBatchAllGood() throws Exception {
        try (RawClient raw = rawClient()) {
            raw.client.writeSingleRegister(60, 111);
            raw.client.writeMultipleRegisters(61, ModbusRegisters.encode(777, PointDataType.INT32, false));
            raw.client.writeSingleCoil(9, true);
        }
        adapter = newAdapter(cfgFull("big"));
        List<PointValue> results = adapter.readPoints(List.of(
                point("b1", "40061", PointDataType.INT16),
                point("b2", "40062", PointDataType.INT32),
                point("b3", "10", PointDataType.BOOL)));
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(pv -> pv.getQuality() == PointQuality.GOOD));
        assertEquals((short) 111, results.get(0).getValue());
        assertEquals(777, ((Number) results.get(1).getValue()).intValue());
        assertEquals(Boolean.TRUE, results.get(2).getValue());
    }

    @Test
    @DisplayName("批量读取遇错即停：失败点记 BAD，后续点不再尝试")
    void readPointsStopsBatchAfterFailure() {
        adapter = newAdapter(cfgFull("big"));
        // 第一个点走离散输入（服务器不支持 → 失败），第二个正常点不应被读取
        List<PointValue> results = adapter.readPoints(List.of(
                point("bad", "10001", PointDataType.BOOL),
                point("good", "40061", PointDataType.INT16)));
        assertEquals(1, results.size());
        assertEquals(PointQuality.BAD, results.get(0).getQuality());
        assertEquals("bad", results.get(0).getPointId());
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("读无效地址：在批量循环内被兜底为 BAD 而不是抛异常")
    void readInvalidAddressReturnsBad() {
        adapter = newAdapter(cfgFull("big"));
        PointValue pv = adapter.readPoint(point("p_bad_addr", "abc", PointDataType.INT16));
        assertEquals(PointQuality.BAD, pv.getQuality());
        assertEquals("p_bad_addr", pv.getPointId());
    }

    // ---------- 写路径 ----------

    @Test
    @DisplayName("写 INT16 单寄存器（功能码 0x06），含负数往返")
    void writeInt16SingleRegister() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("w_i16", "40061", PointDataType.INT16), -5);

        try (RawClient raw = rawClient()) {
            // 线上按无符号 16 位存储
            assertArrayEquals(new int[]{0xFFFB}, raw.client.readHoldingRegisters(60, 1));
        }
        // 适配器读回应还原为负数
        PointValue pv = adapter.readPoint(point("w_i16", "40061", PointDataType.INT16));
        assertEquals((short) -5, pv.getValue());
    }

    @Test
    @DisplayName("写 BOOL 到保持寄存器：true→1，false→0")
    void writeBoolToHoldingRegister() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("w_bool", "40077", PointDataType.BOOL), true);
        adapter.writePoint(point("w_bool2", "40078", PointDataType.BOOL), false);
        try (RawClient raw = rawClient()) {
            assertArrayEquals(new int[]{1}, raw.client.readHoldingRegisters(76, 1));
            assertArrayEquals(new int[]{0}, raw.client.readHoldingRegisters(77, 1));
        }
    }

    @Test
    @DisplayName("写线圈：Boolean/Number/String 三种取值形态")
    void writeCoilValueForms() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("c1", "10", PointDataType.BOOL), Boolean.TRUE);
        adapter.writePoint(point("c2", "11", PointDataType.BOOL), 1);
        adapter.writePoint(point("c3", "12", PointDataType.BOOL), "true");
        adapter.writePoint(point("c4", "10", PointDataType.BOOL), Boolean.FALSE);
        adapter.writePoint(point("c5", "11", PointDataType.BOOL), 0);
        adapter.writePoint(point("c6", "12", PointDataType.BOOL), "false");

        try (RawClient raw = rawClient()) {
            boolean[] coils = raw.client.readCoils(9, 3);
            assertFalse(coils[0]);
            assertFalse(coils[1]);
            assertFalse(coils[2]);
            // 重新写 true 验证正向
            adapter.writePoint(point("c1", "10", PointDataType.BOOL), true);
            assertTrue(raw.client.readCoils(9, 1)[0]);
        }
    }

    @Test
    @DisplayName("写 FLOAT32 高字在前（big/ABCD）：线上传寄存器顺序正确")
    void writeFloat32BigWordOrder() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("w_f32b", "40071", PointDataType.FLOAT32), 25.6f);
        try (RawClient raw = rawClient()) {
            assertArrayEquals(new int[]{0x41CC, 0xCCCD}, raw.client.readHoldingRegisters(70, 2));
        }
    }

    @Test
    @DisplayName("写 FLOAT32 低字在前（little/CDAB）：寄存器字序交换")
    void writeFloat32LittleWordOrder() throws Exception {
        adapter = newAdapter(cfgFull("little"));
        adapter.writePoint(point("w_f32l", "40073", PointDataType.FLOAT32), 25.6f);
        try (RawClient raw = rawClient()) {
            assertArrayEquals(new int[]{0xCCCD, 0x41CC}, raw.client.readHoldingRegisters(72, 2));
        }
    }

    @Test
    @DisplayName("字序影响解读：big 写入的值用 little 读出必然不同")
    void wordOrderAffectsDecoding() throws Exception {
        ModbusTcpAdapter big = newAdapter(cfgFull("big"));
        ModbusTcpAdapter little = newAdapter(cfgFull("little"));
        try {
            big.writePoint(point("wo", "40071", PointDataType.FLOAT32), 25.6f);
            PointValue wrong = little.readPoint(point("wo", "40071", PointDataType.FLOAT32));
            assertEquals(PointQuality.GOOD, wrong.getQuality());
            assertNotEquals(25.6f, ((Number) wrong.getValue()).floatValue(), 1e-6f,
                    "字序不匹配时读数应明显偏离——这正是现场配错 wordOrder 读到量级不对值的机制");
        } finally {
            big.disconnect();
            little.disconnect();
        }
    }

    @Test
    @DisplayName("写 FLOAT64：4 个寄存器按 big 字序落线")
    void writeFloat64Registers() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("w_f64", "40066", PointDataType.FLOAT64), 3.14159265);
        try (RawClient raw = rawClient()) {
            assertArrayEquals(
                    ModbusRegisters.encode(3.14159265, PointDataType.FLOAT64, false),
                    raw.client.readHoldingRegisters(65, 4));
        }
    }

    @Test
    @DisplayName("写 INT32 little 字序：线上传低字在前，little 读回还原")
    void writeInt32LittleRoundTrip() throws Exception {
        adapter = newAdapter(cfgFull("little"));
        adapter.writePoint(point("w_i32l", "40075", PointDataType.INT32), -123456);
        try (RawClient raw = rawClient()) {
            assertArrayEquals(
                    ModbusRegisters.encode(-123456, PointDataType.INT32, true),
                    raw.client.readHoldingRegisters(74, 2));
        }
        PointValue pv = adapter.readPoint(point("w_i32l", "40075", PointDataType.INT32));
        assertEquals(-123456, ((Number) pv.getValue()).intValue());
    }

    @Test
    @DisplayName("地址 0 落到保持寄存器兜底分支（直接地址）")
    void directAddressFallsBackToHolding() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("w_direct", "0", PointDataType.INT16), 777);
        try (RawClient raw = rawClient()) {
            assertArrayEquals(new int[]{777}, raw.client.readHoldingRegisters(0, 1));
        }
    }

    @Test
    @DisplayName("地址 60 落到线圈区（1-9999 → coil）")
    void coilAddressRangeMapping() throws Exception {
        adapter = newAdapter(cfgFull("big"));
        adapter.writePoint(point("w_coil60", "60", PointDataType.BOOL), true);
        try (RawClient raw = rawClient()) {
            assertTrue(raw.client.readCoils(59, 1)[0]);
        }
    }

    @Test
    @DisplayName("写输入寄存器地址被拒绝：Cannot write to address type")
    void writeInputRegisterRejected() {
        adapter = newAdapter(cfgFull("big"));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w_rej", "30001", PointDataType.INT16), 1));
        assertEquals("Modbus write failed", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("INPUT_REGISTER"));
    }

    @Test
    @DisplayName("写非数值到 FLOAT32 被拒绝")
    void writeNonNumericRejected() {
        adapter = newAdapter(cfgFull("big"));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w_nan", "40071", PointDataType.FLOAT32), "abc"));
        assertEquals("Modbus write failed", ex.getMessage());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @DisplayName("写无效地址被拒绝（空串/非数字）")
    void writeInvalidAddressRejected() {
        adapter = newAdapter(cfgFull("big"));
        for (String bad : new String[]{"", "abc"}) {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> adapter.writePoint(point("w_bad", bad, PointDataType.INT16), 1),
                    "地址 " + (bad.isEmpty() ? "<空>" : bad) + " 应被拒绝");
            assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        }
    }

    @Test
    @DisplayName("未连接时写入抛 Not connected")
    void writeWhenNotConnected() {
        ModbusTcpAdapter a = new ModbusTcpAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> a.writePoint(point("w_lc", "40061", PointDataType.INT16), 1));
        assertEquals("Not connected", ex.getMessage());
    }

    // ---------- 辅助 ----------

    private static ModbusTcpAdapter newAdapter(String connectionConfig) {
        ModbusTcpAdapter a = new ModbusTcpAdapter();
        a.connect(channel(connectionConfig));
        return a;
    }

    private static Channel channel(String connectionConfig) {
        Channel ch = new Channel();
        ch.setChannelId("ch_test");
        ch.setProtocolType(ProtocolType.MODBUS_TCP);
        ch.setConnectionConfig(connectionConfig);
        return ch;
    }

    private static String cfgFull(String wordOrder) {
        return "{\"host\":\"localhost\",\"port\":" + port + ",\"unitId\":1,"
                + "\"wordOrder\":\"" + wordOrder + "\"}";
    }

    private static MeasurementPoint point(String id, String address, PointDataType type) {
        MeasurementPoint p = new MeasurementPoint();
        p.setPointId(id);
        p.setPointName(id);
        p.setChannelId("ch_test");
        p.setAddress(address);
        p.setDataType(type);
        return p;
    }

    /** 原始客户端：用于安排/核对服务器侧寄存器的线上真实形态 */
    private static RawClient rawClient() throws IOException {
        ModbusTcpClient client = new ModbusTcpClient();
        client.connect("localhost", port);
        return new RawClient(client);
    }

    private record RawClient(ModbusTcpClient client) implements AutoCloseable {
        @Override
        public void close() {
            client.disconnect();
        }
    }
}
