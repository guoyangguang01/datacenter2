package com.sdncustom.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.repository.PointValueCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final MeasurementPointRepository pointRepository;
    private final PointValueCacheRepository pointValueCache;

    // sessionId -> subscribed channelIds
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    // channelId -> sessionIds
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    // sessionId -> session
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
        sessions.put(session.getId(), session);
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
            List<PointValue> values = new ArrayList<>();
            for (String channelId : channelIds) {
                List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
                for (MeasurementPoint p : points) {
                    pointValueCache.findByPointId(p.getPointId()).ifPresent(values::add);
                }
            }
            if (values.isEmpty()) return;

            Map<String, Object> data = new HashMap<>();
            data.put("type", "data");
            data.put("values", values);
            String json = objectMapper.writeValueAsString(data);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle refresh", e);
        }
    }

    /**
     * 推送测点值到订阅的客户端
     */
    public void pushPointValue(PointValue pointValue) {
        String channelId = pointValue.getSourceChannelId();
        if (channelId == null) {
            MeasurementPoint point = pointRepository.findById(pointValue.getPointId()).orElse(null);
            if (point == null) return;
            channelId = point.getChannelId();
        }

        Set<String> subscribers = channelSubscribers.get(channelId);
        if (subscribers == null || subscribers.isEmpty()) return;

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "data");
            data.put("values", List.of(pointValue));
            String json = objectMapper.writeValueAsString(data);

            TextMessage textMessage = new TextMessage(json);
            for (String sessionId : subscribers) {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(textMessage);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to send to session {}, removing it", sessionId, e);
                        removeSession(sessionId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to push point value", e);
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

            TextMessage textMessage = new TextMessage(json);
            for (String sessionId : subscribers) {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(textMessage);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to send to session {}, removing it", sessionId, e);
                        removeSession(sessionId);
                    }
                }
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
        sessions.remove(sessionId);
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
}
