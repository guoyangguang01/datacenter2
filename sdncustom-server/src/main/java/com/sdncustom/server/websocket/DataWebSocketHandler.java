package com.sdncustom.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.config.WebSocketProperties;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 实时数据 WebSocket 处理器。
 * 每个会话持有独立的有界发送队列与守护发送线程：任何慢/卡死客户端只影响自己，
 * 推送方 offer 永不阻塞；队列溢出即判定慢客户端并断开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final MeasurementPointRepository pointRepository;
    private final PointValueCacheRepository pointValueCache;
    private final WebSocketProperties properties;
    private final MeterRegistry meterRegistry;

    // sessionId -> subscribed channelIds
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    // channelId -> sessionIds
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    // sessionId -> 带独立发送队列的会话
    private final Map<String, SessionSender> sessions = new ConcurrentHashMap<>();
    // pointId -> channelId 内存缓存（避免每次推送查 DB）
    private final Map<String, String> pointChannelCache = new ConcurrentHashMap<>();

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("sdncustom.ws.sessions", sessions, Map::size)
                .description("在线 WebSocket 会话数")
                .register(meterRegistry);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
        sessions.put(session.getId(), new SessionSender(session, properties.getSessionSendQueueCapacity()));
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session.getId());
        log.info("WebSocket disconnected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
            String action = (String) msg.get("action");
            if (action == null) {
                log.warn("WebSocket message missing 'action' field, ignoring");
                return;
            }

            switch (action) {
                case "subscribe" -> handleSubscribe(session, msg);
                case "unsubscribe" -> handleUnsubscribe(session, msg);
                case "refresh" -> handleRefresh(session, msg);
                default -> log.warn("Unknown action: {}", action);
            }
        } catch (Exception e) {
            log.error("Failed to handle WebSocket message", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSubscribe(WebSocketSession session, Map<String, Object> msg) {
        List<String> channelIds = (List<String>) msg.get("channelIds");
        if (channelIds == null) return;

        Set<String> sessionChannels = subscriptions.get(session.getId());
        if (sessionChannels == null) return;

        for (String channelId : channelIds) {
            sessionChannels.add(channelId);
            channelSubscribers.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet())
                    .add(session.getId());
        }
        log.info("Session {} subscribed to channels: {}", session.getId(), channelIds);
    }

    @SuppressWarnings("unchecked")
    private void handleUnsubscribe(WebSocketSession session, Map<String, Object> msg) {
        List<String> channelIds = (List<String>) msg.get("channelIds");
        if (channelIds == null) return;

        Set<String> sessionChannels = subscriptions.get(session.getId());
        if (sessionChannels == null) return;

        for (String channelId : channelIds) {
            sessionChannels.remove(channelId);
            Set<String> subscribers = channelSubscribers.get(channelId);
            if (subscribers != null) {
                subscribers.remove(session.getId());
                if (subscribers.isEmpty()) {
                    channelSubscribers.remove(channelId);
                }
            }
        }
        log.info("Session {} unsubscribed from channels: {}", session.getId(), channelIds);
    }

    @SuppressWarnings("unchecked")
    private void handleRefresh(WebSocketSession session, Map<String, Object> msg) {
        List<String> channelIds = (List<String>) msg.get("channelIds");
        if (channelIds == null) return;

        try {
            Map<String, PointValue> uniqueValues = new LinkedHashMap<>();
            List<String> pointIds = new ArrayList<>();
            for (String channelId : channelIds) {
                for (MeasurementPoint p : pointRepository.findByChannelId(channelId)) {
                    pointIds.add(p.getPointId());
                }
            }
            // 一次 multiGet，别逐点读 Redis
            for (PointValue v : pointValueCache.findByPointIds(pointIds)) {
                uniqueValues.putIfAbsent(v.getPointId(), v);
            }
            List<PointValue> values = new ArrayList<>(uniqueValues.values());
            if (values.isEmpty()) return;

            Map<String, Object> data = new HashMap<>();
            data.put("type", "data");
            data.put("values", values);
            String json = objectMapper.writeValueAsString(data);
            enqueue(session.getId(), json);
        } catch (Exception e) {
            log.error("Failed to handle refresh", e);
        }
    }

    /**
     * 批量推送测点值：按通道分组，每通道一条消息发给其订阅者
     */
    public void pushBatch(List<PointValue> pointValues) {
        Map<String, List<PointValue>> byChannel = new LinkedHashMap<>();
        for (PointValue pv : pointValues) {
            String channelId = resolvePushChannel(pv);
            if (channelId != null) {
                byChannel.computeIfAbsent(channelId, k -> new ArrayList<>()).add(pv);
            }
        }

        for (Map.Entry<String, List<PointValue>> entry : byChannel.entrySet()) {
            try {
                sendJsonToSubscribers(entry.getKey(), buildDataMessage(entry.getValue()));
            } catch (Exception e) {
                log.error("Failed to push batch for channel {}", entry.getKey(), e);
            }
        }
    }

    /**
     * 推送测点值到订阅的客户端
     */
    public void pushPointValue(PointValue pointValue) {
        String channelId = resolvePushChannel(pointValue);
        if (channelId == null) return;
        try {
            String json = buildDataMessage(List.of(pointValue));
            sendJsonToSubscribers(channelId, json);
        } catch (Exception e) {
            log.error("Failed to push point value", e);
        }
    }

    private String resolvePushChannel(PointValue pv) {
        // 先查缓存
        String cached = pointChannelCache.get(pv.getPointId());
        if (cached != null) return cached;
        // 回退到 sourceChannelId
        return pv.getSourceChannelId();
    }

    /**
     * 使缓存失效（测点配置变更时调用）
     */
    public void invalidatePointChannel(String pointId) {
        pointChannelCache.remove(pointId);
    }

    private String buildDataMessage(List<PointValue> values) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "data");
        data.put("values", values);
        return objectMapper.writeValueAsString(data);
    }

    private void sendJsonToSubscribers(String channelId, String json) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        if (subscribers == null || subscribers.isEmpty()) return;

        for (String sessionId : subscribers) {
            enqueue(sessionId, json);
        }
    }

    /**
     * 非阻塞入队；队列满说明该客户端消费不过来，判定为慢客户端直接断开
     */
    private void enqueue(String sessionId, String json) {
        SessionSender sender = sessions.get(sessionId);
        if (sender == null) return;
        if (!sender.offer(json)) {
            meterRegistry.counter("sdncustom.ws.evictions").increment();
            log.warn("Slow WebSocket client evicted (send queue full): {}", sessionId);
            sender.shutdown();
            removeSession(sessionId);
        }
    }

    /**
     * 推送 Channel 状态变化
     */
    public void pushChannelStatus(String channelId, String status) {
        Set<String> subscribers = channelSubscribers.get(channelId);
        if (subscribers == null || subscribers.isEmpty()) return;

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "channel_status");
            data.put("channelId", channelId);
            data.put("status", status);
            String json = objectMapper.writeValueAsString(data);
            for (String sessionId : subscribers) {
                enqueue(sessionId, json);
            }
        } catch (Exception e) {
            log.error("Failed to push channel status", e);
        }
    }

    /**
     * 移除会话及其订阅关系
     */
    private void removeSession(String sessionId) {
        Set<String> channels = subscriptions.remove(sessionId);
        SessionSender sender = sessions.remove(sessionId);
        if (sender != null) {
            sender.shutdown();
        }
        if (channels != null) {
            for (String channelId : channels) {
                Set<String> subscribers = channelSubscribers.get(channelId);
                if (subscribers != null) {
                    subscribers.remove(sessionId);
                    if (subscribers.isEmpty()) {
                        channelSubscribers.remove(channelId);
                    }
                }
            }
        }
    }

    /**
     * 每会话独立发送线程 + 有界队列。发送阻塞只影响本会话；溢出由 offer() 返回 false 由上层断开。
     */
    private static final class SessionSender {
        private final WebSocketSession session;
        private final BlockingQueue<String> outbound;
        private final Thread worker;

        SessionSender(WebSocketSession session, int capacity) {
            this.session = session;
            this.outbound = new LinkedBlockingQueue<>(Math.max(1, capacity));
            this.worker = new Thread(this::run, "ws-send-" + session.getId());
            this.worker.setDaemon(true);
            this.worker.start();
        }

        boolean offer(String json) {
            return worker.isAlive() && outbound.offer(json);
        }

        void shutdown() {
            worker.interrupt();
            closeQuietly();
        }

        private void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String json = outbound.take();
                    session.sendMessage(new TextMessage(json));
                }
            } catch (InterruptedException e) {
                // 关停信号
            } catch (IOException e) {
                log.debug("Send failed for session {}: {}", session.getId(), e.getMessage());
            } finally {
                closeQuietly();
            }
        }

        private void closeQuietly() {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}
