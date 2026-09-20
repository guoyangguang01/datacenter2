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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PropagationService 传播阶段测试")
class PropagationServiceTest {

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
    @DisplayName("积压超阈值：队列深度指标可见，且只告警一次")
    void backlogIsVisibleAndWarnsOnce() throws Exception {
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
        assertTrue(service.backlogWarningActive());

        release.countDown();
        verify(persistenceService, timeout(3000).atLeastOnce()).submitBatch(anyList());
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
    void shutdownDropsAfterTimeout() throws Exception {
        ReflectionTestUtils.setField(service, "drainTimeoutSeconds", 0);
        CountDownLatch release = new CountDownLatch(1);
        // 忽略中断地等待：模拟"设备写出卡住、中断也停不下来"
        when(inputPointPropagator.propagate(anyList())).thenAnswer(inv -> {
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
        service.submitBatch(List.of(value("out_2", "ch_1")));

        long start = System.nanoTime();
        service.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 2000, "排空超时应立即返回，实际 " + elapsedMs + "ms");
        verify(persistenceService, never()).submitBatch(anyList());
        assertEquals(0.0, meterRegistry.get("sdncustom.propagation.queue.depth").gauge().value(),
                "超时强杀后队列应被清空（未处理批次被丢弃）");
        release.countDown();
    }
}
