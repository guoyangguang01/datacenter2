package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("PropagationService 传播阶段测试")
class PropagationServiceTest {

    /** 积压 WARN 的固定前缀，用来在捕获到的日志里数告警条数 */
    private static final String BACKLOG_WARN = "Propagation queue backlog";

    @Mock
    private InputPointPropagator inputPointPropagator;

    @Mock
    private PersistenceService persistenceService;

    @Mock
    private DistributionService distributionService;

    @Mock
    private LiveSourceChecker liveSourceChecker;

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private PropagationService service;

    @BeforeEach
    void setUp() {
        // @InjectMocks 不会注入 @Value 字段，显式给值（阈值调小便于测积压告警）
        ReflectionTestUtils.setField(service, "queueWarnDepth", 2);
        ReflectionTestUtils.setField(service, "drainTimeoutSeconds", 5);
        service.init();
        // 默认：存活过滤原样放行（个别用例再覆盖）
        lenient().when(liveSourceChecker.onlyLive(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PointValue value(String pointId, String sourceChannelId) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setSourceChannelId(sourceChannelId);
        return pv;
    }

    private static List<String> ids(List<PointValue> values) {
        return values.stream().map(PointValue::getPointId).toList();
    }

    /** 数一数本次用例到目前为止打了几条积压 WARN（日志捕获见类上的 OutputCaptureExtension） */
    private static long warnCount(CapturedOutput output) {
        String text = output.getOut();
        long count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(BACKLOG_WARN, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + BACKLOG_WARN.length();
        }
    }

    /** 等 worker 把队列吃空（有界等待，超时即断言失败而不是静默继续） */
    private void awaitQueueDrained() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (meterRegistry.get("sdncustom.propagation.queue.depth").gauge().value() > 0
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0.0, meterRegistry.get("sdncustom.propagation.queue.depth").gauge().value(),
                "worker 未在 3s 内排空积压");
    }

    @Test
    @DisplayName("OUTPUT 与 INPUT 合并成同一批提交给落库，OUTPUT 在前")
    void mergesInputValuesIntoTheSameBatch() {
        PointValue out = value("out_1", "ch_1");
        PointValue in = value("in_1", "ch_2");
        when(inputPointPropagator.propagate(List.of(out))).thenReturn(List.of(in));

        service.submitBatch(List.of(out));

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService, timeout(2000)).submitBatch(captor.capture());
        assertEquals(List.of("out_1", "in_1"), ids(captor.getValue()));
    }

    @Test
    @DisplayName("设备写出阻塞时 submitBatch 立即返回（采集线程不被拖住）")
    void submitBatchReturnsImmediately() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(inputPointPropagator.propagate(anyList())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        long start = System.nanoTime();
        service.submitBatch(List.of(value("out_1", "ch_1")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 500, "submitBatch 不应等待设备写出，实际 " + elapsedMs + "ms");
        release.countDown();
        verify(persistenceService, timeout(3000)).submitBatch(anyList());
    }

    @Test
    @DisplayName("批次按入队顺序处理（FIFO）")
    void batchesProcessedInOrder() {
        when(inputPointPropagator.propagate(anyList())).thenReturn(List.of());

        service.submitBatch(List.of(value("out_1", "ch_1")));
        service.submitBatch(List.of(value("out_2", "ch_1")));

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService, timeout(2000).times(2)).submitBatch(captor.capture());
        assertEquals(List.of("out_1"), ids(captor.getAllValues().get(0)));
        assertEquals(List.of("out_2"), ids(captor.getAllValues().get(1)));
    }

    @Test
    @DisplayName("跨批次顺序：INPUT(N) 不晚于 OUTPUT(N+1) 提交（唯一生产者 + 单线程 FIFO）")
    void inputValuesNeverLagBehindTheNextOutputBatch() {
        PointValue out1 = value("out_1", "ch_1");
        PointValue in1 = value("in_1", "ch_2");
        PointValue out2 = value("out_2", "ch_1");
        when(inputPointPropagator.propagate(List.of(out1))).thenReturn(List.of(in1));
        when(inputPointPropagator.propagate(List.of(out2))).thenReturn(List.of());

        service.submitBatch(List.of(out1));
        service.submitBatch(List.of(out2));

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService, timeout(2000).times(2)).submitBatch(captor.capture());
        List<PointValue> submitted = captor.getAllValues().stream().flatMap(List::stream).toList();
        // 服务是单线程 FIFO，这个顺序是确定的（不依赖时序）
        assertEquals(List.of("out_1", "in_1", "out_2"), ids(submitted),
                "第一批的 INPUT 值必须排在第二批的 OUTPUT 值之前，实际 " + ids(submitted));
    }

    @Test
    @DisplayName("传播抛异常：本批 OUTPUT 值照常落库，后续批次继续处理")
    void propagationFailureDoesNotLoseOutputValues() {
        when(inputPointPropagator.propagate(anyList())).thenThrow(new RuntimeException("boom"));

        service.submitBatch(List.of(value("out_1", "ch_1")));
        service.submitBatch(List.of(value("out_2", "ch_1")));

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService, timeout(2000).times(2)).submitBatch(captor.capture());
        assertEquals(List.of("out_1"), ids(captor.getAllValues().get(0)));
        assertEquals(List.of("out_2"), ids(captor.getAllValues().get(1)));
        assertEquals(2.0, meterRegistry.get("sdncustom.propagation.batch.errors").counter().count());
    }

    @Test
    @DisplayName("INPUT 值不过推送前的存活过滤（来源通道是写出目标，掉线只代表没送达）")
    void inputValuesBypassLivenessFilter() {
        PointValue out = value("out_1", "ch_1");
        PointValue in = value("in_1", "ch_dead");
        when(inputPointPropagator.propagate(anyList())).thenReturn(List.of(in));
        when(liveSourceChecker.onlyLive(anyList())).thenReturn(List.of());  // 输出值全被滤掉

        service.submitBatch(List.of(out));

        ArgumentCaptor<List<PointValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(distributionService, timeout(2000)).pushBatch(captor.capture());
        assertEquals(List.of("in_1"), ids(captor.getValue()));
    }

    @Test
    @DisplayName("积压超阈值：队列深度指标可见，打一次 WARN，且积压期内不重复刷屏")
    void backlogIsVisibleAndWarnsOnce(CapturedOutput output) throws Exception {
        CountDownLatch workerInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(inputPointPropagator.propagate(anyList())).thenAnswer(inv -> {
            workerInside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        service.submitBatch(List.of(value("out_0", "ch_1")));
        assertTrue(workerInside.await(2, TimeUnit.SECONDS), "worker 未开始处理第一批");
        // worker 阻塞在写出，后续批次留在队列里（阈值 2）
        for (int i = 1; i <= 3; i++) {
            service.submitBatch(List.of(value("out_" + i, "ch_1")));
        }

        assertEquals(3.0, meterRegistry.get("sdncustom.propagation.queue.depth").gauge().value());
        assertEquals(1L, warnCount(output), "越过 warn-depth 应恰好打一条 WARN，实际日志：\n" + output);
        assertTrue(output.getOut().contains("Propagation queue backlog: 3 batch(es) pending (warn depth 2)"),
                "WARN 应带上积压深度与阈值，实际日志：\n" + output);

        // 积压未消退时继续提交：深度涨到 4，仍不应产生第二条 WARN（不刷屏）
        service.submitBatch(List.of(value("out_4", "ch_1")));
        assertEquals(1L, warnCount(output), "积压期内不应重复告警，实际日志：\n" + output);

        release.countDown();
        verify(persistenceService, timeout(3000).atLeastOnce()).submitBatch(anyList());
    }

    @Test
    @DisplayName("积压告警在积压消退后复位：排空再积压会重新告警")
    void backlogWarningResetsAfterBacklogDrains(CapturedOutput output) throws Exception {
        // 可开关的闸门：blockWorker 为真时 worker 卡在设备写出上
        AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));
        AtomicBoolean blockWorker = new AtomicBoolean(true);
        when(inputPointPropagator.propagate(anyList())).thenAnswer(inv -> {
            if (blockWorker.get()) {
                gate.get().await(5, TimeUnit.SECONDS);
            }
            return List.of();
        });

        // 第一次积压：4 批入队（阈值 2）→ 一次告警
        for (int i = 0; i <= 3; i++) {
            service.submitBatch(List.of(value("out_" + i, "ch_1")));
        }
        assertEquals(1L, warnCount(output), "首次越过 warn-depth 应打一条 WARN，实际日志：\n" + output);

        // 放行排空 → 之后的提交会看到深度回落到阈值以下，复位告警标志
        blockWorker.set(false);
        gate.get().countDown();
        awaitQueueDrained();
        assertEquals(1L, warnCount(output), "排空过程本身不应再告警，实际日志：\n" + output);

        // 再次积压：重新卡住 worker，重新越过阈值 → 新的一条告警（证明标志位确实复位过）
        gate.set(new CountDownLatch(1));
        blockWorker.set(true);
        for (int i = 4; i <= 7; i++) {
            service.submitBatch(List.of(value("out_" + i, "ch_1")));
        }
        assertEquals(2L, warnCount(output), "复位后再次越过 warn-depth 应重新告警，实际日志：\n" + output);

        blockWorker.set(false);
        gate.get().countDown();
    }

    @Test
    @DisplayName("停机：排空窗口内把在途批次处理完")
    void shutdownDrainsInFlightBatch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(inputPointPropagator.propagate(anyList())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        service.submitBatch(List.of(value("out_1", "ch_1")));
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            release.countDown();
        });
        releaser.start();

        service.shutdown();

        verify(persistenceService, timeout(2000)).submitBatch(anyList());
        releaser.join(2000);
    }

    @Test
    @DisplayName("停机：排空超时立即返回、不无限等待，并把未处理批次丢弃")
    void shutdownDropsAfterTimeout(CapturedOutput output) throws Exception {
        ReflectionTestUtils.setField(service, "drainTimeoutSeconds", 0);
        CountDownLatch workerInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 忽略中断地等待：模拟"设备写出卡住、中断也停不下来"
        when(inputPointPropagator.propagate(anyList())).thenAnswer(inv -> {
            workerInside.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 停机时的 shutdownNow 会中断，这里刻意忽略
                }
            }
            return List.of();
        });

        service.submitBatch(List.of(value("out_1", "ch_1")));
        assertTrue(workerInside.await(2, TimeUnit.SECONDS), "worker 未开始处理第一批");
        // 确认 worker 已卡在第一批上，队列里只剩第二批——丢弃条数因此是确定的
        service.submitBatch(List.of(value("out_2", "ch_1")));

        long start = System.nanoTime();
        service.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 2000, "排空超时应立即返回，实际 " + elapsedMs + "ms");
        verify(persistenceService, never()).submitBatch(anyList());
        assertEquals(0.0, meterRegistry.get("sdncustom.propagation.queue.depth").gauge().value(),
                "超时强杀后队列应被清空（未处理批次被丢弃）");
        // 丢的是"队列里的 1 批 + 在途的 1 批"，不能只报队列深度（那会把在途那批排除在外）
        assertTrue(output.getOut().contains(
                        "Propagation queue not drained in 0s, dropping 2 batch(es) (1 queued plus the in-flight one)"),
                "丢弃告警应计入在途批次，实际日志：\n" + output);
        release.countDown();
    }
}
