package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.PointDirection;
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
    private final LiveSourceChecker liveSourceChecker;
    private final MeasurementPointRepository pointRepository;
    private final ChannelService channelService;
    private final ChangeGate changeGate;
    private final PropagationService propagationService;
    private final ProtocolRegistry protocolRegistry;
    private final MeterRegistry meterRegistry;

    /** 等待本轮采集完成的上限；超时后未完成的任务不再等，其数据归入下一轮 */
    private static final long CYCLE_WAIT_SECONDS = 5;

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
     * 定时采集任务：各通道并行读取，经变更门过滤 + 存活复核后，**仅把有效变化提交给
     * {@link PropagationService}**（只入队、立即返回）。
     *
     * <p>落库、推送与设备写出都不在这里发生——它们全在传播阶段的后台线程上完成，见
     * {@code PropagationService.process}。
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

            List<CompletableFuture<List<PointValue>>> futures = channels.stream()
                    .map(channel -> CompletableFuture
                            .supplyAsync(() -> acquireChannel(channel), executor)
                            .exceptionally(e -> {
                                meterRegistry.counter("sdncustom.acquisition.failures",
                                        "channel", channel.getChannelId()).increment();
                                log.error("Acquisition failed for channel: {}", channel.getChannelId(), e);
                                return List.of();
                            }))
                    .toList();

            // 等待本轮全部完成（限时），防止慢通道无限拖长周期
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(CYCLE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Acquisition cycle timed out waiting for channels ({} of {} finished)",
                        futures.stream().filter(CompletableFuture::isDone).count(), futures.size());
            }

            // 只消费已完成任务的结果：超时后仍在跑的任务归属下一轮，
            // 不跨周期共享可变列表（旧实现会让迟到结果与本轮读取竞争）
            List<PointValue> changedValues = futures.stream()
                    .filter(CompletableFuture::isDone)
                    .flatMap(f -> f.join().stream())
                    .toList();

            // 本轮无有效变化则零提交，避免重复数据打爆存储与推送通道
            if (!changedValues.isEmpty()) {
                // 读取期间用户可能已断开通道：断开来源的迟到值不写缓存/历史、也不下发到设备
                List<PointValue> publishable = liveSourceChecker.onlyLive(changedValues);
                if (!publishable.isEmpty()) {
                    meterRegistry.counter("sdncustom.acquisition.changed.values")
                            .increment(publishable.size());

                    // 传播（设备写出）与落库/推送都在传播阶段的后台线程上完成：
                    // 采集线程自身不执行任何设备 I/O，也不等落库/推送。
                    // 一处残留：通道同时挂两种方向时，读写共用适配器的那把锁（锁覆盖整个往返），
                    // 在飞的写仍可能把该通道的读顶出上面 5s 的等待窗口——所以要写成
                    // "采集线程不做写出"，而不是"采集周期不再受慢设备影响"。
                    // 顺序保证：进 PersistenceService 的生产者只有 PropagationService 一个，
                    // 且 INPUT 值与其来源 OUTPUT 值在该阶段被合并成同一批。
                    propagationService.submitBatch(publishable);
                }
            }
        } finally {
            meterRegistry.timer("sdncustom.acquisition.cycle").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 采集单个 Channel 的数据，返回通过变更检测的值
     */
    private List<PointValue> acquireChannel(Channel channel) {
        // 只读 OUTPUT：INPUT 测点的值由传播写出，不从通道读
        List<MeasurementPoint> points = pointRepository.findByChannelIdAndDirection(
                channel.getChannelId(), PointDirection.OUTPUT);

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
