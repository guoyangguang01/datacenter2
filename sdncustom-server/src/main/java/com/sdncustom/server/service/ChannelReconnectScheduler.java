package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 断线自动重连。非 MQTT 协议没有自带重连：适配器报断后采集引擎只把状态改成 DISCONNECTED，
 * 通道就一直躺到人工点"连接"。这里对「期望保持连接」的通道按退避重试。
 *
 * <p>MQTT 不归这里管（Paho 自带自动重连，再叠一层会去重建适配器实例）。
 * 用户主动 disconnect 的通道会被移出期望集合，不会被拉回来。
 * 退避：{@code initial * multiplier^(连续失败次数-1)}，封顶 {@code max-delay}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelReconnectScheduler {

    private final ChannelService channelService;
    private final MeterRegistry meterRegistry;

    @Value("${sdncustom.channel.reconnect.initial-delay-ms:2000}")
    private long initialDelayMs;

    @Value("${sdncustom.channel.reconnect.max-delay-ms:60000}")
    private long maxDelayMs;

    @Value("${sdncustom.channel.reconnect.multiplier:2.0}")
    private double multiplier;

    /** channelId -> 下次允许尝试的时间戳 */
    private final Map<String, Long> nextAttemptAt = new ConcurrentHashMap<>();
    /** channelId -> 连续失败次数 */
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    private Counter reconnects;
    private Counter reconnectFailures;

    @PostConstruct
    void registerMetrics() {
        reconnects = meterRegistry.counter("sdncustom.channel.reconnects");
        reconnectFailures = meterRegistry.counter("sdncustom.channel.reconnect.failures");
    }

    @Scheduled(fixedDelayString = "${sdncustom.channel.reconnect.scan-interval-ms:5000}")
    public void reconnectDesiredChannels() {
        Set<String> desired = channelService.desiredConnectedIds();
        // 通道被删除或用户主动断开后，清掉它的退避状态
        nextAttemptAt.keySet().retainAll(desired);
        failures.keySet().retainAll(desired);
        if (desired.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String channelId : desired) {
            Long nextAt = nextAttemptAt.get(channelId);
            if (nextAt != null && now < nextAt) {
                continue;
            }

            Channel channel = channelService.findByIdOrNull(channelId);
            if (channel == null) {
                reset(channelId);
                continue;
            }
            if (channel.getStatus() == ChannelStatus.CONNECTED) {
                reset(channelId);
                continue;
            }
            if (channel.getProtocolType() == ProtocolType.MQTT) {
                continue;
            }

            try {
                channelService.connect(channelId);
                reset(channelId);
                reconnects.increment();
                log.info("Auto-reconnected channel: {}", channelId);
            } catch (Exception e) {
                int attempts = failures.merge(channelId, 1, Integer::sum);
                long delay = backoffMs(attempts);
                nextAttemptAt.put(channelId, System.currentTimeMillis() + delay);
                reconnectFailures.increment();
                log.warn("Auto-reconnect failed for channel {} (attempt {}), retrying in {}ms: {}",
                        channelId, attempts, delay, e.getMessage());
            }
        }
    }

    private long backoffMs(int attempts) {
        double raw = initialDelayMs * Math.pow(multiplier, Math.max(0, attempts - 1));
        return (long) Math.min(maxDelayMs, raw);
    }

    private void reset(String channelId) {
        failures.remove(channelId);
        nextAttemptAt.remove(channelId);
    }
}
