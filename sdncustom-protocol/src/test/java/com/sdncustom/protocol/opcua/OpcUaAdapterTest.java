package com.sdncustom.protocol.opcua;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.mock.MockOpcUaServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpcUaAdapter 集成测试：对接进程内的 MockOpcUaServer。
 *
 * <p>不能用 mockConstruction 拦 Milo 客户端——{@code OpcUaClient.create()} 里就有同步的
 * GetEndpoints discovery，真实网络不可达时在构造之前就抛了。MockOpcUaServer.start()
 * 吞异常，所以 @BeforeAll 用 TCP 探针确认它真的在监听。
 *
 * <p>断言策略：模拟器每 2s 对数值节点做随机波动（写入可能被覆盖），写读回环用
 * 有限重试消化竞态；读断言只锚定质量与类型，不锚定会被波动改变的数值。
 */
@DisplayName("OpcUaAdapter 适配器集成测试")
class OpcUaAdapterTest {

    private static MockOpcUaServer server;
    private static String endpoint;

    private OpcUaAdapter adapter;

    @BeforeAll
    static void startServer() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new MockOpcUaServer(port);
        server.start();

        boolean listening = false;
        for (int i = 0; i < 30 && !listening; i++) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("localhost", port), 200);
                listening = true;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        if (!listening) {
            throw new IllegalStateException("MockOpcUaServer 未能在端口 " + port + " 启动（start() 吞掉了异常）");
        }
        endpoint = "opc.tcp://localhost:" + port;
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
    @DisplayName("连接成功（含 discovery）：isConnected 为 true，断开后为 false")
    void connectAndDisconnect() {
        adapter = newAdapter(endpoint);
        assertTrue(adapter.isConnected(), "会话建立后 isConnected 应为 true");

        adapter.disconnect();
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("连接失败（端点拒绝）：抛 OPC-UA connection failed")
    void connectFailureThrows() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        adapter = new OpcUaAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.connect(channel("opc.tcp://localhost:" + deadPort)));
        assertEquals("OPC-UA connection failed", ex.getMessage());
        assertFalse(adapter.isConnected());
    }

    // ---------- 读取 ----------

    @Test
    @DisplayName("读 FLOAT64 节点：GOOD + Double，时间戳来自服务端源时间")
    void readFloat64Node() {
        adapter = newAdapter(endpoint);
        PointValue pv = adapter.readPoint(point("p_f64", "ns=2;s=Temperature/Room1", PointDataType.FLOAT64));

        assertEquals("p_f64", pv.getPointId());
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertInstanceOf(Double.class, pv.getValue());
        assertTrue(pv.getTimestamp() > 0, "服务端返回源时间戳");
        assertEquals("ch_test", pv.getSourceChannelId());
    }

    @Test
    @DisplayName("读 BOOL 节点：GOOD + Boolean")
    void readBoolNode() {
        adapter = newAdapter(endpoint);
        PointValue pv = adapter.readPoint(point("p_bool", "ns=2;s=Status/Pump01", PointDataType.BOOL));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertInstanceOf(Boolean.class, pv.getValue());
    }

    @Test
    @DisplayName("读 STRING 节点：GOOD + String")
    void readStringNode() {
        adapter = newAdapter(endpoint);
        PointValue pv = adapter.readPoint(point("p_str", "ns=2;s=Mode/Operation", PointDataType.STRING));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertInstanceOf(String.class, pv.getValue());
    }

    @Test
    @DisplayName("读 INT32 节点：GOOD + Integer")
    void readInt32Node() {
        adapter = newAdapter(endpoint);
        PointValue pv = adapter.readPoint(point("p_i32", "ns=2;s=Counter/Production", PointDataType.INT32));
        assertEquals(PointQuality.GOOD, pv.getQuality());
        assertInstanceOf(Integer.class, pv.getValue());
    }

    @Test
    @DisplayName("批量读多个节点：全部 GOOD 且逐点带配置的 pointId")
    void readBatchAllGood() {
        adapter = newAdapter(endpoint);
        List<PointValue> results = adapter.readPoints(List.of(
                point("b1", "ns=2;s=Temperature/Room1", PointDataType.FLOAT64),
                point("b2", "ns=2;s=Status/Valve01", PointDataType.BOOL),
                point("b3", "ns=2;s=Power/Voltage", PointDataType.FLOAT64)));

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(pv -> pv.getQuality() == PointQuality.GOOD));
        assertEquals("b1", results.get(0).getPointId());
        assertEquals("b2", results.get(1).getPointId());
        assertEquals("b3", results.get(2).getPointId());
    }

    @Test
    @DisplayName("读不存在的节点：服务端回坏状态码 → 该点 BAD，不中断批量")
    void readUnknownNodeYieldsBadWithoutBreakingBatch() {
        adapter = newAdapter(endpoint);
        List<PointValue> results = adapter.readPoints(List.of(
                point("gone", "ns=2;s=Does/Not/Exist", PointDataType.FLOAT64),
                point("alive", "ns=2;s=Temperature/Room1", PointDataType.FLOAT64)));

        assertEquals(2, results.size());
        assertEquals(PointQuality.BAD, results.get(0).getQuality());
        assertNull(results.get(0).getValue());
        assertEquals(PointQuality.GOOD, results.get(1).getQuality(),
                "OPC-UA 单点失败不应中断整批（与 Modbus 的遇错即停不同）");
    }

    @Test
    @DisplayName("无效 NodeId（缺 s=/i= 标识）：该点 BAD 而不是抛异常")
    void readInvalidNodeIdYieldsBad() {
        adapter = newAdapter(endpoint);
        PointValue pv = adapter.readPoint(point("p_bad", "ns=2", PointDataType.INT16));
        assertEquals(PointQuality.BAD, pv.getQuality());
        assertEquals("p_bad", pv.getPointId());
    }

    @Test
    @DisplayName("未连接读取：readPoints 抛 Not connected，readPoint 兜底 BAD")
    void readWhenNotConnected() {
        adapter = new OpcUaAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.readPoints(List.of(point("p", "ns=2;s=x", PointDataType.INT16))));
        assertEquals("Not connected", ex.getMessage());
        assertEquals(PointQuality.BAD,
                adapter.readPoint(point("p", "ns=2;s=x", PointDataType.INT16)).getQuality());
    }

    // ---------- 写入 ----------

    @Test
    @DisplayName("写 FLOAT64 后回读一致（写入可能被模拟波动覆盖，有限重试消化竞态）")
    void writeFloat64RoundTrip() {
        adapter = newAdapter(endpoint);
        assertWriteThenReadBack(
                point("w_f64", "ns=2;s=Temperature/Room2", PointDataType.FLOAT64), 123.45);
    }

    @Test
    @DisplayName("写 INT32 后回读一致")
    void writeInt32RoundTrip() {
        adapter = newAdapter(endpoint);
        assertWriteThenReadBack(
                point("w_i32", "ns=2;s=Counter/Production", PointDataType.INT32), 70000);
    }

    @Test
    @DisplayName("写 BOOL 后回读一致")
    void writeBoolRoundTrip() {
        adapter = newAdapter(endpoint);
        assertWriteThenReadBack(
                point("w_bool", "ns=2;s=Status/Valve01", PointDataType.BOOL), Boolean.TRUE);
    }

    @Test
    @DisplayName("写 STRING 后回读一致（String 节点不波动，一次即中）")
    void writeStringRoundTrip() {
        adapter = newAdapter(endpoint);
        assertWriteThenReadBack(
                point("w_str", "ns=2;s=Mode/Operation", PointDataType.STRING), "AUTO");
    }

    @Test
    @DisplayName("写入非数值到 FLOAT64：本地解析失败，包装为 OPC-UA write failed")
    void writeNonNumericRejected() {
        adapter = newAdapter(endpoint);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w_nan", "ns=2;s=Temperature/Room1", PointDataType.FLOAT64), "abc"));
        assertEquals("OPC-UA write failed", ex.getMessage());
        assertInstanceOf(NumberFormatException.class, ex.getCause());
    }

    @Test
    @DisplayName("无效 NodeId 写入：被拒绝而不是发到错误节点")
    void writeInvalidNodeIdRejected() {
        adapter = newAdapter(endpoint);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w_id", "ns=2", PointDataType.INT16), 1));
        assertEquals("OPC-UA write failed", ex.getMessage());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @DisplayName("写入不存在的节点：服务端坏状态码 → OPC-UA write failed")
    void writeUnknownNodeRejected() {
        adapter = newAdapter(endpoint);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w_none", "ns=2;s=Does/Not/Exist", PointDataType.FLOAT64), 1.0));
        assertEquals("OPC-UA write failed", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("OPC-UA write failed:"),
                "应点名服务端返回的坏状态码，实际: " + ex.getCause().getMessage());
    }

    @Test
    @DisplayName("未连接时写入抛 Not connected")
    void writeWhenNotConnected() {
        adapter = new OpcUaAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w_lc", "ns=2;s=x", PointDataType.INT16), 1));
        assertEquals("Not connected", ex.getMessage());
    }

    // ---------- 辅助 ----------

    /**
     * 写→读回环。模拟器每 2s 用它自己的旧值覆盖节点，单次写读可能撞上波动周期，
     * 有限重试把竞态压到可忽略（每次撞上的概率约 1%，8 次约 1e-16）。
     */
    private void assertWriteThenReadBack(MeasurementPoint point, Object value) {
        for (int attempt = 0; attempt < 8; attempt++) {
            adapter.writePoint(point, value);
            PointValue back = adapter.readPoint(point);
            if (value.equals(back.getValue())) {
                assertEquals(PointQuality.GOOD, back.getQuality());
                return;
            }
        }
        fail("写入 " + value + " 后回读始终不一致: " + point.getAddress()
                + "（回读=" + adapter.readPoint(point).getValue() + "）");
    }

    private OpcUaAdapter newAdapter(String endpointUrl) {
        OpcUaAdapter a = new OpcUaAdapter();
        a.connect(channel(endpointUrl));
        return a;
    }

    private static Channel channel(String endpointUrl) {
        Channel ch = new Channel();
        ch.setChannelId("ch_test");
        ch.setConnectionConfig("{\"endpoint\":\"" + endpointUrl + "\"}");
        return ch;
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
}
