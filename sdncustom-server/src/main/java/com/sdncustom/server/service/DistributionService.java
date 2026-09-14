package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import com.sdncustom.server.config.WebSocketProperties;
import com.sdncustom.server.websocket.DataWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 实时推送分发。采集调度线程仅提交任务到专用单线程队列，绝不因慢客户端阻塞采集周期；
 * 队列满时丢弃最旧批次（监控场景保最新数据）。会话级超时/缓冲背压由 handler 的
 * ConcurrentWebSocketSessionDecorator 承担，慢客户端会被自动断开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributionService {

    private final DataWebSocketHandler webSocketHandler;
    private final WebSocketProperties properties;

    private ExecutorService pushExecutor;

    @PostConstruct
    void init() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, properties.getPushQueueCapacity())),
                r -> new Thread(r, "ws-push"),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        this.pushExecutor = executor;
    }

    @PreDestroy
    void shutdown() {
        if (pushExecutor != null) {
            pushExecutor.shutdownNow();
        }
    }

    /**
     * 推送单个测点值（异步提交）
     */
    public void push(PointValue pointValue) {
        pushExecutor.submit(() -> webSocketHandler.pushPointValue(pointValue));
    }

    /**
     * 批量推送测点值（按通道合帧，异步提交；快照隔离调用方后续可能的列表变更）
     */
    public void pushBatch(List<PointValue> pointValues) {
        if (pointValues.isEmpty()) {
            return;
        }
        List<PointValue> snapshot = List.copyOf(pointValues);
        pushExecutor.submit(() -> webSocketHandler.pushBatch(snapshot));
    }

    /**
     * 推送通道状态变化（连接/断开/异常），供监控页实时反映通道状态
     */
    public void pushChannelStatus(String channelId, String status) {
        pushExecutor.submit(() -> webSocketHandler.pushChannelStatus(channelId, status));
    }
}
