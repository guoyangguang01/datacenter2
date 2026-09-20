package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 传播（设备写出）后台阶段。采集调度线程只提交快照，绝不等一次设备写出——
 * {@code acquire()} 是 {@code @Scheduled(fixedDelay)}，写在它里面的耗时会 1:1 吃掉采集频率
 * （backlog A9：一台写超时的设备会拖长所有通道共用的周期）。
 *
 * <p><b>线程数必须恒为 1，且本服务是 {@link PersistenceService} 的唯一生产者</b>：单线程 + FIFO
 * 是批次间保序的手段；又因为 INPUT 值与其来源 OUTPUT 值在这里被合并成**同一批**提交，
 * "INPUT 传播值不会晚于下一轮 OUTPUT 值落库"这一保证原样成立。为吞吐调大 corePoolSize 会破坏它。
 *
 * <p><b>队列无界</b>（写命令不是遥测，丢弃语义更重）。只有"通道已连接但写得慢"才会积压：
 * 通道未连接时 {@link InputPointPropagator} 直接跳过、根本不入队。深度超阈值打 WARN，
 * 让无界增长的 OOM 风险在发生之前可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropagationService {

    private final InputPointPropagator inputPointPropagator;
    private final PersistenceService persistenceService;
    private final DistributionService distributionService;
    private final LiveSourceChecker liveSourceChecker;
    private final MeterRegistry meterRegistry;

    /** 队列深度超过它即告警一次（无界队列的安全阀） */
    @Value("${sdncustom.propagation.queue-warn-depth:100}")
    private int queueWarnDepth;

    /** 停机排空窗口；超时强杀并丢弃剩余批次——这是唯一会丢写命令的路径 */
    @Value("${sdncustom.propagation.drain-timeout-seconds:5}")
    private int drainTimeoutSeconds;

    private ThreadPoolExecutor propagationExecutor;
    private Counter batchErrors;
    private volatile boolean backlogWarned;

    @PostConstruct
    void init() {
        batchErrors = Counter.builder("sdncustom.propagation.batch.errors")
                .description("传播阶段批次级兜底异常数（非 0 说明有批次被整批放弃，需查日志）")
                .register(meterRegistry);

        this.propagationExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), // 无界：不丢写命令
                r -> new Thread(r, "propagation"));

        Gauge.builder("sdncustom.propagation.queue.depth", propagationExecutor, e -> e.getQueue().size())
                .description("传播队列积压的批次数（无界队列，持续增长会耗尽内存）")
                .register(meterRegistry);
    }

    @PreDestroy
    void shutdown() {
        if (propagationExecutor == null) {
            return;
        }
        // 策略是"不丢写命令"，所以不能像 DistributionService 那样直接 shutdownNow：
        // 先给一个有界排空窗口（比 PersistenceService 的 2s 长——写出是设备 I/O）
        propagationExecutor.shutdown();
        try {
            if (!propagationExecutor.awaitTermination(drainTimeoutSeconds, TimeUnit.SECONDS)) {
                // 丢的不止队列里的：shutdownNow() 的中断会连带放弃正在写出的那一批
                int queued = propagationExecutor.getQueue().size();
                log.warn("Propagation queue not drained in {}s, dropping {} batch(es) ({} queued plus the in-flight one) — 这些写命令不会送达设备",
                        drainTimeoutSeconds, queued + 1, queued);
                propagationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            propagationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 采集线程调用：只入队，立即返回（快照隔离调用方后续可能的列表变更） */
    public void submitBatch(List<PointValue> outputChanges) {
        if (outputChanges.isEmpty()) {
            return;
        }
        List<PointValue> snapshot = List.copyOf(outputChanges);
        propagationExecutor.submit(() -> process(snapshot));
        warnIfBacklogged();
    }

    /** 无界队列的可见性：积压超阈值打一次 WARN，回落到阈值以下后重置（同一积压期不刷屏） */
    private void warnIfBacklogged() {
        int depth = propagationExecutor.getQueue().size();
        if (depth <= queueWarnDepth) {
            backlogWarned = false;
            return;
        }
        if (!backlogWarned) {
            backlogWarned = true;
            log.warn("Propagation queue backlog: {} batch(es) pending (warn depth {}) — "
                    + "设备写得比数据变化慢；队列无界，持续增长会耗尽内存", depth, queueWarnDepth);
        }
    }

    private void process(List<PointValue> outputChanges) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // 传播只影响 INPUT 测点；它失败不能连累 OUTPUT 值的落库——changeGate 已经推进过变更基线，
            // 这一轮的值丢了就永久丢（Redis、历史、推送三处都不会再见到）
            List<PointValue> inputValues = propagateSafely(outputChanges);

            List<PointValue> allValues = new ArrayList<>(outputChanges);
            allValues.addAll(inputValues);
            persistenceService.submitBatch(allValues);

            // 推送前再复核：设备写出期间用户若断开，迟到的 GOOD 会把前端的 COMM_LOST 刷回正常。
            // INPUT 值不过这道滤网——它们的来源通道是**写出目标**，掉线只代表没送达，不代表值失效。
            List<PointValue> pushable = new ArrayList<>(liveSourceChecker.onlyLive(outputChanges));
            pushable.addAll(inputValues);
            if (!pushable.isEmpty()) {
                distributionService.pushBatch(pushable);
            }
        } catch (Exception e) {
            // 兜底：本批整批放弃——OUTPUT 与 INPUT 值都不落库、不推送。changeGate 已经推进过变更基线，
            // 这些值不会再有第二次机会，留下的痕迹只有这条日志与 batchErrors 指标。
            // 只 catch Exception 是刻意的（不要放宽到 Throwable）：Error（如 OOM）继续上抛，
            // 线程池会补一个 worker 接着处理后续批次，所以不会"静默停写"；代价是那一批连日志与指标都没有。
            batchErrors.increment();
            log.error("Propagation batch failed; {} output value(s) plus any propagated input values "
                    + "were not persisted or pushed", outputChanges.size(), e);
        } finally {
            sample.stop(meterRegistry.timer("sdncustom.propagation.batch.seconds"));
        }
    }

    private List<PointValue> propagateSafely(List<PointValue> outputChanges) {
        try {
            return inputPointPropagator.propagate(outputChanges);
        } catch (Exception e) {
            batchErrors.increment();
            log.error("Input point propagation failed; committing output values only", e);
            return List.of();
        }
    }
}
