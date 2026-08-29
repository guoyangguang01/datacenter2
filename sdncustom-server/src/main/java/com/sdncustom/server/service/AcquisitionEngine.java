package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AcquisitionEngine {

    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;
    private final PointSourceService pointSourceService;
    private final ChannelService channelService;
    private final PointService pointService;
    private final HistoryService historyService;
    private final DistributionService distributionService;
    private final ChangeGate changeGate;
    private final ProtocolRegistry protocolRegistry;
    private final MeterRegistry meterRegistry;

    // 每通道独立线程并行采集，避免一个慢通道拖垮整个采集周期
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    private final AtomicInteger connectedChannels = new AtomicInteger();

    @PostConstruct
    void registerMetrics() {
        io.micrometer.core.instrument.Timer.builder("sdncustom.acquisition.cycle")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        Gauge.builder("sdncustom.acquisition.channels.connected", connectedChannels, AtomicInteger::get)
                .register(meterRegistry);
    }

    /**
     * 定时采集任务：各通道并行读取，经变更门过滤后仅对有效变化做批量落库与推送
     */
    @Scheduled(fixedDelayString = "${sdncustom.acquisition.interval-ms:200}")
    public void acquire() {
        long start = System.nanoTime();
        try {
            List<Channel> channels = channelRepository.findByStatus(ChannelStatus.CONNECTED);
            connectedChannels.set(channels.size());

            if (channels.isEmpty()) {
                return;
            }

            log.debug("Acquiring data from {} connected channels", channels.size());

            List<PointValue> changedValues = Collections.synchronizedList(new ArrayList<>());

            List<CompletableFuture<Void>> futures = channels.stream()
                    .map(channel -> CompletableFuture.runAsync(() -> {
                        try {
                            changedValues.addAll(acquireChannel(channel));
                        } catch (Exception e) {
                            meterRegistry.counter("sdncustom.acquisition.failures",
                                    "channel", channel.getChannelId()).increment();
                            log.error("Acquisition failed for channel: {}", channel.getChannelId(), e);
                        }
                    }, executor))
                    .toList();

            // 等待本轮全部完成（限时），防止慢通道无限拖长周期
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Acquisition cycle timed out waiting for channels");
            }

            // 本轮无有效变化则零写入，避免重复数据打爆存储与推送通道
            if (!changedValues.isEmpty()) {
                meterRegistry.counter("sdncustom.acquisition.changed.values")
                        .increment(changedValues.size());
                pointService.updateBatch(changedValues);
                historyService.saveBatch(changedValues);
                distributionService.pushBatch(changedValues);
            }
        } finally {
            meterRegistry.timer("sdncustom.acquisition.cycle").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 采集单个 Channel 的数据，返回通过变更检测的值
     */
    private List<PointValue> acquireChannel(Channel channel) {
        List<MeasurementPoint> points = pointSourceService.findPointsForChannel(channel.getChannelId());

        if (points.isEmpty()) {
            log.debug("No points configured for channel: {}", channel.getChannelId());
            return List.of();
        }

        ProtocolAdapter adapter = protocolRegistry.getOrCreate(channel);
        if (!adapter.isConnected()) {
            log.debug("Adapter not connected for channel: {}", channel.getChannelId());
            // 非 MQTT 协议无自动重连：若 DB 状态仍为 CONNECTED，则修正为 DISCONNECTED，消除"假连接"
            if (channel.getProtocolType() != ProtocolType.MQTT) {
                channelService.syncDisconnected(channel.getChannelId());
            }
            return List.of();
        }

        List<PointValue> values = adapter.readPoints(points);
        log.debug("Read {} values from channel: {}", values.size(), channel.getChannelId());

        Map<String, MeasurementPoint> pointsById = new HashMap<>();
        for (MeasurementPoint point : points) {
            pointsById.put(point.getPointId(), point);
        }
        return changeGate.filter(values, pointsById);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
