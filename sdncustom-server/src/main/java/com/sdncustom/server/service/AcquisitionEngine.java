package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import com.sdncustom.protocol.ProtocolRegistry;
import com.sdncustom.server.repository.ChannelRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final PointSourceService pointSourceService;
    private final ChannelService channelService;
    private final PointService pointService;
    private final HistoryService historyService;
    private final DistributionService distributionService;
    private final ChangeGate changeGate;
    private final InputPointPropagator inputPointPropagator;
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

            // 本轮无有效变化则零写入，避免重复数据打爆存储与推送通道
            if (!changedValues.isEmpty()) {
                // 读取期间用户可能已断开通道：断开来源的迟到值不写缓存/历史
                List<PointValue> publishable = onlyLiveSources(changedValues);
                if (!publishable.isEmpty()) {
                    meterRegistry.counter("sdncustom.acquisition.changed.values")
                            .increment(publishable.size());

                    // 输入测点传播：同步写出到各自绑定通道，值并入本轮批次
                    List<PointValue> inputValues = propagateSafely(publishable);

                    List<PointValue> allValues = new ArrayList<>(publishable);
                    allValues.addAll(inputValues);

                    pointService.updateBatch(allValues);
                    historyService.saveBatch(allValues);

                    // 推送前再复核一次：上面两次落库可能很慢（Redis 超时会阻塞数秒），
                    // 期间用户若断开，迟到的 GOOD 会在 COMM_LOST 之后把前端刷回正常。
                    // 过滤放在推送前一刻，窗口就只剩一次查询的距离。
                    // 输入测点值不过这道滤网：它们的来源通道是**写出目标**，
                    // 目标掉线只代表没送达，不代表这个值本身失效（决策 A）。
                    List<PointValue> pushable = new ArrayList<>(onlyLiveSources(publishable));
                    pushable.addAll(inputValues);
                    if (!pushable.isEmpty()) {
                        distributionService.pushBatch(pushable);
                    }
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
        List<MeasurementPoint> points = pointSourceService.findOutputPointsForChannel(channel.getChannelId());

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

    /**
     * 传播必须就地失败：{@code changeGate.filter} 已经推进了本轮的变更基线，异常若从这里逃出去，
     * 这一轮（含所有通道）的变化值就永久丢了——Redis、历史、推送三处都不会再见到它们。
     * 传播只影响 INPUT 测点，不该连累 OUTPUT 值的落库，所以吞掉异常、记指标、按"没有输入测点"继续。
     */
    private List<PointValue> propagateSafely(List<PointValue> publishable) {
        try {
            return inputPointPropagator.propagate(publishable);
        } catch (Exception e) {
            meterRegistry.counter("sdncustom.propagation.errors").increment();
            log.error("Input point propagation failed; committing output values only", e);
            return List.of();
        }
    }

    /**
     * 丢弃来源通道已不在 CONNECTED 的迟到值。通道断开时 markPointsCommLost 会推 COMM_LOST，
     * 若这些迟到值随后再推送，客户端会被刷回 GOOD——按 DB 状态（客户端看到的状态投影）过滤。
     */
    private List<PointValue> onlyLiveSources(List<PointValue> values) {
        Set<String> live = channelRepository.findByStatus(ChannelStatus.CONNECTED).stream()
                .map(Channel::getChannelId)
                .collect(Collectors.toSet());
        return values.stream()
                .filter(pv -> pv.getSourceChannelId() == null || live.contains(pv.getSourceChannelId()))
                .toList();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
