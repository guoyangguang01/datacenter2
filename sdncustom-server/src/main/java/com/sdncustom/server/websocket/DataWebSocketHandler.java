package com.sdncustom.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.repository.MeasurementPointRepository;
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

    // sessionId -> subscribed channelIds
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    // channelId -> sessionIds
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<String> channels = subscriptions.remove(session.getId());
        if (channels != null) {
            for (String channelId : channels) {
                Set<String> subscribers = channelSubscribers.get(channelId);
                if (subscribers != null) {
                    subscribers.remove(session.getId());
                    if (subscribers.isEmpty()) {
                        channelSubscribers.remove(channelId);
                    }
                }
            }
        }
        log.info("WebSocket disconnected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
            String action = (String) msg.get("action");

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

        // 获取所有测点值并推送
        for (String channelId : channelIds) {
            List<MeasurementPoint> points = pointRepository.findByChannelId(channelId);
            // 这里需要从缓存获取值，简化处理
            log.info("Refresh requested for channel: {}", channelId);
        }
    }

    /**
     * 推送测点值到订阅的客户端
     */
    public void pushPointValue(PointValue pointValue) {
        MeasurementPoint point = pointRepository.findById(pointValue.getPointId()).orElse(null);
        if (point == null) return;

        String channelId = point.getChannelId();
        Set<String> subscribers = channelSubscribers.get(channelId);
        if (subscribers == null || subscribers.isEmpty()) return;

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "data");
            data.put("values", List.of(pointValue));
            String json = objectMapper.writeValueAsString(data);

            TextMessage textMessage = new TextMessage(json);
            for (String sessionId : subscribers) {
                // 需要获取 session 并发送
                // 这里简化处理，实际需要维护 session 引用
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
                // 需要获取 session 并发送
            }
        } catch (Exception e) {
            log.error("Failed to push channel status", e);
        }
    }
}
