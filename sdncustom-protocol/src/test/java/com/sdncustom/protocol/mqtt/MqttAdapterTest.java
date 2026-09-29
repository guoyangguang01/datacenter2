package com.sdncustom.protocol.mqtt;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MqttAdapter 测试：用 mockConstruction 拦截 Paho 客户端，不依赖真实 broker。
 * 连接失败路径走真实客户端连拒绝端口（本机无 broker 时 Paho 立即抛连接拒绝）。
 */
@DisplayName("MqttAdapter 适配器测试")
class MqttAdapterTest {

    private MqttAdapter adapter;

    @AfterEach
    void tearDown() throws Exception {
        if (adapter != null) {
            adapter.disconnect();
            adapter = null;
        }
    }

    // ---------- 连接生命周期 ----------

    @Test
    @DisplayName("连接成功：应用配置项、注册回调、标记已连接")
    void connectAppliesConfigAndRegistersCallback() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel("{\"broker\":\"tcp://broker:1883\",\"clientId\":\"client_a\","
                    + "\"username\":\"user1\",\"password\":\"secret\"}"));

            assertEquals(1, mc.constructed().size());
            MqttClient client = mc.constructed().get(0);

            ArgumentCaptor<MqttConnectionOptions> optCap =
                    ArgumentCaptor.forClass(MqttConnectionOptions.class);
            verify(client).connect(optCap.capture());
            MqttConnectionOptions options = optCap.getValue();
            assertEquals("user1", options.getUserName());
            assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), options.getPassword());
            assertTrue(options.isCleanStart());
            assertTrue(options.isAutomaticReconnect());
            verify(client).setCallback(any(MqttCallback.class));

            when(client.isConnected()).thenReturn(true);
            assertTrue(adapter.isConnected());
        }
    }

    @Test
    @DisplayName("配置省略 username/password：连接选项不带认证信息")
    void connectWithoutCredentials() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));

            ArgumentCaptor<MqttConnectionOptions> optCap =
                    ArgumentCaptor.forClass(MqttConnectionOptions.class);
            verify(mc.constructed().get(0)).connect(optCap.capture());
            assertNull(optCap.getValue().getUserName());
            assertNull(optCap.getValue().getPassword());
        }
    }

    @Test
    @DisplayName("连接失败（broker 拒绝）：抛 MQTT connection failed 且未连接")
    void connectFailureThrowsAndStaysDisconnected() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        adapter = new MqttAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.connect(channel(
                        "{\"broker\":\"tcp://127.0.0.1:" + freePort + "\",\"clientId\":\"client_fail\"}")));
        assertEquals("MQTT connection failed", ex.getMessage());
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("连接配置非法 JSON：同样抛 MQTT connection failed 并清理")
    void connectInvalidConfigThrows() throws Exception {
        adapter = new MqttAdapter();
        assertThrows(RuntimeException.class, () -> adapter.connect(channel("{not json")));
        assertFalse(adapter.isConnected());
    }

    // ---------- 消息到达与读取 ----------

    @Test
    @DisplayName("消息到达：JSON 数字入缓存，readPoint 用测点配置的 pointId 返回 GOOD")
    void messageArrivedCachesAndReadPointCopiesPointId() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttCallback callback = captureCallback(mc.constructed().get(0));

            callback.messageArrived("sensors/temp",
                    new MqttMessage("25.6".getBytes(StandardCharsets.UTF_8)));

            PointValue pv = adapter.readPoint(point("p_t", "sensors/temp"));
            assertEquals("p_t", pv.getPointId());
            assertEquals(25.6, ((Number) pv.getValue()).doubleValue());
            assertEquals(PointQuality.GOOD, pv.getQuality());
            assertEquals("ch_test", pv.getSourceChannelId());
            assertTrue(pv.getTimestamp() > 0);
        }
    }

    @Test
    @DisplayName("消息载荷解析：非 JSON 存字符串，JSON 对象存 Map，空载荷存 null")
    void messageArrivedPayloadParsing() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttCallback callback = captureCallback(mc.constructed().get(0));

            callback.messageArrived("t/str",
                    new MqttMessage("hello".getBytes(StandardCharsets.UTF_8)));
            callback.messageArrived("t/obj",
                    new MqttMessage("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
            callback.messageArrived("t/empty", new MqttMessage(new byte[0]));

            assertEquals("hello", adapter.readPoint(point("p1", "t/str")).getValue());
            Object obj = adapter.readPoint(point("p2", "t/obj")).getValue();
            assertInstanceOf(java.util.Map.class, obj);
            PointValue empty = adapter.readPoint(point("p3", "t/empty"));
            assertNull(empty.getValue());
            assertEquals(PointQuality.GOOD, empty.getQuality());
        }
    }

    @Test
    @DisplayName("未收到过消息的 topic：readPoint 返回 COMM_LOST 而不是 null")
    void readPointWithoutCachedMessageReturnsCommLost() throws Exception {
        adapter = new MqttAdapter();
        PointValue pv = adapter.readPoint(point("p_miss", "never/arrived"));
        assertEquals(PointQuality.COMM_LOST, pv.getQuality());
        assertNull(pv.getValue());
    }

    @Test
    @DisplayName("readPoints：有缓存的 GOOD + 无缓存的 COMM_LOST，逐点返回")
    void readPointsMixesCachedAndMissing() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttCallback callback = captureCallback(mc.constructed().get(0));
            callback.messageArrived("t/hit",
                    new MqttMessage("7".getBytes(StandardCharsets.UTF_8)));

            List<PointValue> results = adapter.readPoints(List.of(
                    point("p_hit", "t/hit"),
                    point("p_gap", "t/gap")));

            assertEquals(2, results.size());
            assertEquals("p_hit", results.get(0).getPointId());
            assertEquals(7, ((Number) results.get(0).getValue()).intValue());
            assertEquals(PointQuality.GOOD, results.get(0).getQuality());
            assertEquals("p_gap", results.get(1).getPointId());
            assertEquals(PointQuality.COMM_LOST, results.get(1).getQuality());
        }
    }

    // ---------- 写入 ----------

    @Test
    @DisplayName("writePoint：以 JSON 载荷 QoS1 发布到测点 topic")
    void writePointPublishesJsonWithQos1() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);

            adapter.writePoint(point("w1", "cmd/valve", PointDataType.FLOAT64), 42.5);

            ArgumentCaptor<MqttMessage> cap = ArgumentCaptor.forClass(MqttMessage.class);
            verify(client).publish(eq("cmd/valve"), cap.capture());
            assertEquals("42.5", new String(cap.getValue().getPayload(), StandardCharsets.UTF_8));
            assertEquals(1, cap.getValue().getQos());
        }
    }

    @Test
    @DisplayName("未连接时写入抛 Not connected")
    void writeWhenNotConnected() throws Exception {
        adapter = new MqttAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.writePoint(point("w2", "cmd/x", PointDataType.INT16), 1));
        assertEquals("Not connected", ex.getMessage());
    }

    // ---------- 订阅 / 退订 ----------

    @Test
    @DisplayName("订阅幂等：同一 topic 重复订阅只调一次")
    void subscribeIsIdempotent() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);

            adapter.subscribe("t/a");
            adapter.subscribe("t/a");

            verify(client, times(1)).subscribe(eq("t/a"), eq(1));
        }
    }

    @Test
    @DisplayName("订阅失败：抛异常并回滚订阅状态（下次可重试）")
    void subscribeFailureRollsBackState() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);
            doThrow(new MqttException(0))
                    .when(client).subscribe(eq("t/b"), eq(1));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> adapter.subscribe("t/b"));
            assertEquals("MQTT subscribe failed", ex.getMessage());

            // 回滚生效：再次 subscribe 会重新尝试（而不是被幂等逻辑吞掉）
            assertThrows(RuntimeException.class, () -> adapter.subscribe("t/b"));
            verify(client, times(2)).subscribe(eq("t/b"), eq(1));
        }
    }

    @Test
    @DisplayName("未连接时订阅抛 Not connected")
    void subscribeWhenNotConnected() throws Exception {
        adapter = new MqttAdapter();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.subscribe("t/x"));
        assertEquals("Not connected", ex.getMessage());
    }

    @Test
    @DisplayName("onConnected 按测点地址批量订阅")
    void onConnectedSubscribesAllAddresses() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);

            adapter.onConnected(List.of(point("p1", "t/1"), point("p2", "t/2")));

            verify(client).subscribe(eq("t/1"), eq(1));
            verify(client).subscribe(eq("t/2"), eq(1));
        }
    }

    @Test
    @DisplayName("断线重连回调：恢复全部已订阅 topic")
    void connectCompleteResubscribesTopics() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);
            MqttCallback callback = captureCallback(client);
            when(client.isConnected()).thenReturn(true);

            adapter.subscribe("t/r");
            // broker 侧重连成功 → 恢复订阅（初始订阅 1 次 + 恢复 1 次）
            callback.connectComplete(true, "tcp://broker:1883");

            verify(client, times(2)).subscribe(eq("t/r"), eq(1));
        }
    }

    @Test
    @DisplayName("退订：调 broker 退订并清掉缓存值，topic 可重新订阅")
    void unsubscribeClearsCacheAndAllowsResubscribe() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);
            when(client.isConnected()).thenReturn(true);

            adapter.subscribe("t/u");
            MqttCallback callback = captureCallback(client);
            callback.messageArrived("t/u", new MqttMessage("9".getBytes(StandardCharsets.UTF_8)));
            assertEquals(PointQuality.GOOD, adapter.readPoint(point("p_u", "t/u")).getQuality());

            adapter.unsubscribe("t/u");
            verify(client).unsubscribe("t/u");
            // 缓存已清：读回 COMM_LOST
            assertEquals(PointQuality.COMM_LOST, adapter.readPoint(point("p_u", "t/u")).getQuality());
            // 订阅状态已回滚：可重新订阅
            adapter.subscribe("t/u");
            verify(client, times(2)).subscribe(eq("t/u"), eq(1));
        }
    }

    @Test
    @DisplayName("退订未订阅的 topic / 空 topic：不触碰 broker")
    void unsubscribeSkipsUnknownAndBlankTopics() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);

            adapter.unsubscribe("never/subscribed");
            adapter.unsubscribe(null);
            adapter.unsubscribe("  ");

            verify(client, never()).unsubscribe(anyString());
        }
    }

    @Test
    @DisplayName("退订时 client 未连上：只清本地状态，不调 broker")
    void unsubscribeWithDeadClientClearsLocalStateOnly() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);
            when(client.isConnected()).thenReturn(false);

            adapter.subscribe("t/d");
            MqttCallback callback = captureCallback(client);
            callback.messageArrived("t/d", new MqttMessage("1".getBytes(StandardCharsets.UTF_8)));

            adapter.unsubscribe("t/d");

            verify(client, never()).unsubscribe(anyString());
            assertEquals(PointQuality.COMM_LOST, adapter.readPoint(point("p_d", "t/d")).getQuality());
        }
    }

    @Test
    @DisplayName("onPointsRemoved：逐个退订并清缓存")
    void onPointsRemovedUnsubscribesEachTopic() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);
            when(client.isConnected()).thenReturn(true);

            adapter.onConnected(List.of(point("p1", "t/rm1"), point("p2", "t/rm2")));
            MqttCallback callback = captureCallback(client);
            callback.messageArrived("t/rm1", new MqttMessage("1".getBytes(StandardCharsets.UTF_8)));

            adapter.onPointsRemoved(List.of(point("p1", "t/rm1"), point("p2", "t/rm2")));

            verify(client).unsubscribe("t/rm1");
            verify(client).unsubscribe("t/rm2");
            assertEquals(PointQuality.COMM_LOST, adapter.readPoint(point("p1", "t/rm1")).getQuality());
        }
    }

    // ---------- 回调 ----------

    @Test
    @DisplayName("断开回调：connected 置 false，isConnected 立即反映")
    void disconnectedCallbackFlipsConnectedFlag() throws Exception {
        try (MockedConstruction<MqttClient> mc = mockConstruction(MqttClient.class)) {
            adapter = new MqttAdapter();
            adapter.connect(channel(cfg()));
            MqttClient client = mc.constructed().get(0);
            MqttCallback callback = captureCallback(client);
            when(client.isConnected()).thenReturn(true);
            assertTrue(adapter.isConnected());

            callback.disconnected(mock(MqttDisconnectResponse.class));
            assertFalse(adapter.isConnected());

            // 各回调入口被调用不抛异常
            callback.mqttErrorOccurred(mock(MqttException.class));
            callback.deliveryComplete(mock(IMqttToken.class));
        }
    }

    // ---------- 辅助 ----------

    private static MqttCallback captureCallback(MqttClient client) {
        ArgumentCaptor<MqttCallback> cap = ArgumentCaptor.forClass(MqttCallback.class);
        verify(client).setCallback(cap.capture());
        return cap.getValue();
    }

    private static String cfg() {
        return "{\"broker\":\"tcp://broker:1883\",\"clientId\":\"client_t\"}";
    }

    private static Channel channel(String connectionConfig) {
        Channel ch = new Channel();
        ch.setChannelId("ch_test");
        ch.setConnectionConfig(connectionConfig);
        return ch;
    }

    private static MeasurementPoint point(String id, String topic) {
        return point(id, topic, PointDataType.FLOAT64);
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
