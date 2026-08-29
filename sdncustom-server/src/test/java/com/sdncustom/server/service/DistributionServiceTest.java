package com.sdncustom.server.service;

import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.server.config.WebSocketProperties;
import com.sdncustom.server.websocket.DataWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("DistributionService 异步推送测试")
class DistributionServiceTest {

    private DataWebSocketHandler handler;
    private WebSocketProperties properties;
    private DistributionService service;

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
        handler = mock(DataWebSocketHandler.class);
        properties = new WebSocketProperties();
        service = new DistributionService(handler, properties);
        service.init();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("pushBatch 异步移交：慢 handler 不阻塞提交线程")
    void pushBatchDoesNotBlockCaller() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            blocking.await(5, TimeUnit.SECONDS);
            return null;
        }).when(handler).pushBatch(anyList());

        long start = System.nanoTime();
        service.pushBatch(List.of(value("p1")));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.pushBatch(List.of(value("p2"))); // 提交不等待上一个完成
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500, "caller blocked " + elapsedMs + "ms");

        blocking.countDown();
        verify(handler, timeout(2000).times(2)).pushBatch(anyList());
    }

    @Test
    @DisplayName("队列满时丢弃最旧批次，保留最新")
    void queueFullDiscardsOldest() throws Exception {
        properties.setPushQueueCapacity(1);
        service.shutdown();
        service.init();

        CountDownLatch blocking = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            blocking.await(5, TimeUnit.SECONDS);
            return null;
        }).when(handler).pushBatch(anyList());

        List<PointValue> b1 = List.of(value("p1"));
        List<PointValue> b2 = List.of(value("p2"));
        List<PointValue> b3 = List.of(value("p3"));

        service.pushBatch(b1);           // 出队并开始执行（阻塞）
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.pushBatch(b2);           // 入队
        service.pushBatch(b3);           // 队满 -> 丢弃 b2

        blocking.countDown();

        verify(handler, timeout(2000)).pushBatch(b1);
        verify(handler, timeout(2000)).pushBatch(b3);
        verify(handler, after(500).never()).pushBatch(b2);
    }

    @Test
    @DisplayName("空批次不提交")
    void emptyBatchSkipped() {
        service.pushBatch(List.of());
        verify(handler, after(200).never()).pushBatch(anyList());
    }

    @Test
    @DisplayName("pushBatch 快照隔离：提交后修改原列表不影响推送内容")
    void snapshotIsolatesCallerMutation() throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        List<PointValue>[] holder = new List[1];
        doAnswer(inv -> {
            holder[0] = inv.getArgument(0);
            captured.countDown();
            return null;
        }).when(handler).pushBatch(anyList());

        java.util.List<PointValue> mutable = new java.util.ArrayList<>();
        mutable.add(value("p1"));
        service.pushBatch(mutable);
        mutable.clear(); // 提交后立即修改原列表

        assertTrue(captured.await(2, TimeUnit.SECONDS));
        assertEquals(1, holder[0].size());
    }
}
