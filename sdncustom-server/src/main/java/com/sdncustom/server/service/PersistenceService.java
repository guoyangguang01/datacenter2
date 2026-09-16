package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 落库（Redis 实时缓存 + TDengine 历史）后台队列。采集调度线程只提交快照，绝不等一次
 * Redis 往返（{@code spring.data.redis.timeout: 3000}）或一次 TDengine 写入；
 * {@code acquire()} 是 {@code @Scheduled(fixedDelay)}，写在它里面的耗时会 1:1 吃掉采集频率。
 *
 * <p><b>线程数必须恒为 1</b>：单线程 + FIFO 队列是批次间保序的唯一手段，保证 INPUT
 * 传播值不会晚于下一轮 OUTPUT 值落库（backlog A9 记录的顺序隐患）。为吞吐调大 corePoolSize
 * 会破坏这一点。
 *
 * <p>队列满时丢弃最旧批次并计 {@code sdncustom.persistence.queue.dropped}，代价是该批次的
 * 实时缓存与历史同时永久缺失——这是相对"同步写（只是慢）"新增的损失模式，故必须有指标可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistenceService {

    private final PointService pointService;
    private final HistoryService historyService;
    private final MeterRegistry meterRegistry;

    /** 待落库批次队列容量，满则丢最旧批次 */
    @Value("${sdncustom.persistence.queue-capacity:200}")
    private int queueCapacity;

    private ThreadPoolExecutor storeExecutor;
    private Counter dropped;
    private Counter errors;

    @PostConstruct
    void init() {
        dropped = Counter.builder("sdncustom.persistence.queue.dropped")
                .description("落库队列满被丢弃的最旧批次数（该批次的实时缓存与历史永久缺失）")
                .register(meterRegistry);
        errors = Counter.builder("sdncustom.persistence.errors")
                .description("后台落库单步失败次数（异常已吞，不影响采集周期与后续批次）")
                .register(meterRegistry);

        this.storeExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> new Thread(r, "point-store"),
                new ThreadPoolExecutor.DiscardOldestPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        dropped.increment();
                        log.warn("Persistence queue full, dropping oldest batch (capacity {})", queueCapacity);
                        super.rejectedExecution(r, e);
                    }
                });

        Gauge.builder("sdncustom.persistence.queue.depth", storeExecutor, e -> e.getQueue().size())
                .description("落库队列积压的批次数")
                .register(meterRegistry);
    }

    @PreDestroy
    void shutdown() {
        if (storeExecutor == null) {
            return;
        }
        // 与 DistributionService 的 shutdownNow 不同：停机时先给队列一个有界排空窗口，
        // 避免把已经排进队列的历史批次直接丢掉
        storeExecutor.shutdown();
        try {
            if (!storeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Persistence queue not drained in 2s, dropping {} batch(es)",
                        storeExecutor.getQueue().size());
                storeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            storeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 批量提交待落库的值（异步返回，绝不阻塞采集线程；快照隔离调用方后续可能的列表变更）
     */
    public void submitBatch(List<PointValue> pointValues) {
        if (pointValues.isEmpty()) {
            return;
        }
        List<PointValue> snapshot = List.copyOf(pointValues);
        storeExecutor.submit(() -> store(snapshot));
    }

    /**
     * 后台单线程串行落库。两步各自独立 try/catch：Redis 抛异常不跳过同批的历史写入，
     * 且任何异常都不得逃出后台线程——逃出去会让后续批次再也不被处理（静默停写）。
     */
    private void store(List<PointValue> batch) {
        try {
            pointService.updateBatch(batch);
        } catch (Exception e) {
            errors.increment();
            log.error("Failed to update realtime cache for {} values", batch.size(), e);
        }
        try {
            historyService.saveBatch(batch);
        } catch (Exception e) {
            errors.increment();
            log.error("Failed to persist history for {} values", batch.size(), e);
        }
    }
}
