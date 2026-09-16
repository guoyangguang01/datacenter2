package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointQuality;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("PersistenceService 落库队列测试")
class PersistenceServiceTest {

    private PointService pointService;
    private HistoryService historyService;
    private SimpleMeterRegistry meterRegistry;
    private PersistenceService service;

    private PointValue value(String id) {
        PointValue pv = new PointValue();
        pv.setPointId(id);
        pv.setValue(1.0);
        pv.setQuality(PointQuality.GOOD);
        pv.setSourceChannelId("ch_1");
        return pv;
    }

    @BeforeEach
    void setUp() {
        pointService = mock(PointService.class);
        historyService = mock(HistoryService.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new PersistenceService(pointService, historyService, meterRegistry);
        ReflectionTestUtils.setField(service, "queueCapacity", 200);
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("submitBatch 异步移交：落库慢也不阻塞提交线程")
    void submitBatchDoesNotBlockCaller() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            blocking.await(5, TimeUnit.SECONDS);
            return null;
        }).when(pointService).updateBatch(anyList());

        long start = System.nanoTime();
        service.submitBatch(List.of(value("p1")));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.submitBatch(List.of(value("p2"))); // 提交不等待上一个完成
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500, "caller blocked " + elapsedMs + "ms");

        blocking.countDown();
        verify(pointService, timeout(2000).times(2)).updateBatch(anyList());
    }

    @Test
    @DisplayName("先写实时缓存再写历史（与改造前次序一致）")
    void realtimeCacheWrittenBeforeHistory() {
        List<PointValue> batch = List.of(value("p1"));

        service.submitBatch(batch);

        InOrder inOrder = inOrder(pointService, historyService);
        inOrder.verify(pointService, timeout(2000)).updateBatch(batch);
        inOrder.verify(historyService, timeout(2000)).saveBatch(batch);
    }

    @Test
    @DisplayName("单线程 FIFO：批次按提交顺序落库")
    void batchesStoredInSubmissionOrder() throws Exception {
        List<String> stored = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            List<PointValue> arg = inv.getArgument(0);
            stored.add(arg.get(0).getPointId());
            if (stored.size() == 1) {
                entered.countDown();
                blocking.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(pointService).updateBatch(anyList());

        service.submitBatch(List.of(value("p1")));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.submitBatch(List.of(value("p2")));
        service.submitBatch(List.of(value("p3")));
        blocking.countDown();

        verify(pointService, timeout(2000).times(3)).updateBatch(anyList());
        assertEquals(List.of("p1", "p2", "p3"), stored);
    }

    @Test
    @DisplayName("队列满时丢弃最旧批次并计数")
    void queueFullDiscardsOldestAndCounts() throws Exception {
        ReflectionTestUtils.setField(service, "queueCapacity", 1);
        service.shutdown();
        service.init();

        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            blocking.await(5, TimeUnit.SECONDS);
            return null;
        }).when(pointService).updateBatch(anyList());

        List<PointValue> b1 = List.of(value("p1"));
        List<PointValue> b2 = List.of(value("p2"));
        List<PointValue> b3 = List.of(value("p3"));

        service.submitBatch(b1);        // 出队并开始执行（阻塞）
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.submitBatch(b2);        // 入队
        service.submitBatch(b3);        // 队满 -> 丢弃 b2

        blocking.countDown();

        verify(pointService, timeout(2000)).updateBatch(b1);
        verify(pointService, timeout(2000)).updateBatch(b3);
        verify(pointService, after(500).never()).updateBatch(b2);
        // 拒绝策略在提交线程内同步执行，返回时计数已加完，无需等待
        assertEquals(1.0, meterRegistry.get("sdncustom.persistence.queue.dropped").counter().count());
    }

    @Test
    @DisplayName("落库抛异常不打断后台线程：同批历史照写、后续批次照常处理")
    void failureDoesNotKillTheStoreThread() {
        List<PointValue> b1 = List.of(value("p1"));
        List<PointValue> b2 = List.of(value("p2"));
        doThrow(new RuntimeException("redis down")).when(pointService).updateBatch(b1);

        service.submitBatch(b1);
        service.submitBatch(b2);

        // Redis 失败不跳过同批的历史写入（两个 try 块互相独立）
        verify(historyService, timeout(2000)).saveBatch(b1);
        // 后台线程没死，后续批次仍被处理
        verify(historyService, timeout(2000)).saveBatch(b2);
        assertEquals(1.0, meterRegistry.get("sdncustom.persistence.errors").counter().count());
    }

    @Test
    @DisplayName("快照隔离：提交后修改原列表不影响落库内容")
    void snapshotIsolatesCallerMutation() throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        List<PointValue>[] holder = new List[1];
        doAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            captured.countDown();
            return null;
        }).when(pointService).updateBatch(anyList());

        List<PointValue> mutable = new ArrayList<>();
        mutable.add(value("p1"));
        service.submitBatch(mutable);
        mutable.clear(); // 提交后立即修改原列表

        assertTrue(captured.await(2, TimeUnit.SECONDS));
        assertEquals(1, holder[0].size());
    }

    @Test
    @DisplayName("空批次不提交")
    void emptyBatchSkipped() {
        service.submitBatch(List.of());

        verify(pointService, after(200).never()).updateBatch(anyList());
    }

    @Test
    @DisplayName("启动时注册队列指标")
    void metricsRegistered() {
        assertNotNull(meterRegistry.get("sdncustom.persistence.queue.depth").gauge());
        assertNotNull(meterRegistry.get("sdncustom.persistence.queue.dropped").counter());
    }

    @Test
    @DisplayName("停机先给队列排空窗口：已入队的批次仍会落库，不直接丢弃")
    void shutdownDrainsQueuedBatches() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(pointService).updateBatch(anyList());

        List<PointValue> b1 = List.of(value("p1"));
        List<PointValue> b2 = List.of(value("p2"));
        service.submitBatch(b1);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.submitBatch(b2); // 排在队列里

        // 300ms 后才放开第一批（模拟慢写入）：shutdown 应当等它排空，而不是立刻强杀
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(300);
                release.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        releaser.start();

        service.shutdown();

        verify(pointService, timeout(2000)).updateBatch(b1);
        verify(pointService, timeout(2000)).updateBatch(b2);
    }
}
