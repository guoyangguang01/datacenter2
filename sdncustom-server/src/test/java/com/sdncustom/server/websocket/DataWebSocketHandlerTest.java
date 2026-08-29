package com.sdncustom.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.server.config.WebSocketProperties;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DataWebSocketHandler 会话级背压测试")
class DataWebSocketHandlerTest {

    private MeasurementPointRepository pointRepository;
    private WebSocketProperties properties;
    private DataWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        pointRepository = mock(MeasurementPointRepository.class);
        PointValueCacheRepository cache = mock(PointValueCacheRepository.class);
        properties = new WebSocketProperties();
        handler = new DataWebSocketHandler(new ObjectMapper(), pointRepository, cache, properties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private PointValue value(String id) {
        PointValue pv = new PointValue();
        pv.setPointId(id);
        pv.setValue(1.0);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_1");
        pv.setTimestamp(System.currentTimeMillis());
        return pv;
    }

    private WebSocketSession stalledSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        // 发送永久阻塞：模拟 TCP 窗口关闭、客户端完全不读
        doAnswer(inv -> {
            Thread.sleep(600_000);
            return null;
        }).when(session).sendMessage(any());
        return session;
    }

    private void subscribe(String sessionId, List<String> channelIds) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        handler.handleTextMessage(session,
                new org.springframework.web.socket.TextMessage(
                        "{\"action\":\"subscribe\",\"channelIds\":" + toJsonArray(channelIds) + "}"));
    }

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("慢客户端发送队列溢出后被断开并清理订阅")
    void slowClientEvictedOnQueueOverflow() throws Exception {
        properties.setSessionSendQueueCapacity(2);
        WebSocketSession stalled = stalledSession("slow");
        handler.afterConnectionEstablished(stalled);
        subscribe("slow", List.of("ch_1"));
        when(pointRepository.findById("p1")).thenReturn(Optional.of(new MeasurementPoint()));

        // 连续推送：第 1 条被 worker 阻塞持有，随后填满队列(2)，再下一条触发溢出断开
        for (int i = 0; i < 30; i++) {
            handler.pushPointValue(value("p1"));
            Thread.sleep(20);
            if (!stalled.isOpen()) break;
        }

        verify(stalled, timeout(3000).atLeastOnce()).close();
        // 被清除后继续推送不应再报错（会话已不在表中）
        handler.pushPointValue(value("p1"));
    }

    @Test
    @DisplayName("一个会话阻塞不影响其它会话接收（会话级隔离）")
    void stalledSessionDoesNotBlockOthers() throws Exception {
        WebSocketSession stalled = stalledSession("slow");
        WebSocketSession normal = mock(WebSocketSession.class);
        when(normal.getId()).thenReturn("fast");
        when(normal.isOpen()).thenReturn(true);
        when(pointRepository.findById("p1")).thenReturn(Optional.of(new MeasurementPoint()));

        handler.afterConnectionEstablished(stalled);
        handler.afterConnectionEstablished(normal);
        subscribe("slow", List.of("ch_1"));
        subscribe("fast", List.of("ch_1"));

        // 卡死会话收不到/收到多少无所谓；健康会话应正常收到多条
        for (int i = 0; i < 5; i++) {
            handler.pushPointValue(value("p1"));
            Thread.sleep(30);
        }

        verify(normal, timeout(2000).atLeast(3)).sendMessage(any());
        verify(normal, never()).close();
    }

    @Test
    @DisplayName("pushBatch 按通道合帧：一次调用一条消息")
    void pushBatchCoalescesPerChannel() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ok");
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        subscribe("ok", List.of("ch_1"));

        handler.pushBatch(List.of(value("p1"), value("p2"), value("p3")));

        verify(session, timeout(2000).times(1)).sendMessage(any());
    }
}
