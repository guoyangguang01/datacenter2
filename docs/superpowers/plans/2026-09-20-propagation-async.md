# 传播异步化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 InputPointPropagator 的设备写出从采集调度线程搬到独立的单线程阶段，使 `acquire()` 的耗时只由"读取 + 过滤 + 入队"构成，同时保持落库/推送的批次顺序语义完全不变。

**Architecture:** 新增 `PropagationService`（单线程 + 无界 FIFO），采集线程只 `submitBatch(快照)` 立即返回；该线程完成设备写出后，把 `[OUTPUT + INPUT]` 合并成**一批**提交 `PersistenceService` 与 `DistributionService`。采集线程**不再直接提交**落库/推送——这样进 `PersistenceService` 的生产者唯一，批次间保序与今天等价。

**Tech Stack:** Java 23 / Spring Boot 3.2.5 / Micrometer / JUnit 5 + Mockito（`SimpleMeterRegistry`、`verify(timeout())`）

**Spec:** `docs/superpowers/specs/2026-09-20-propagation-async-design.md`

## Global Constraints

- **`PropagationService` 的线程数恒为 1**：单线程 + FIFO 是批次间保序的唯一手段（`PersistenceService` 类注释同款约定），不得为吞吐调大。
- **队列无界，绝不丢写命令**（spec §3 决策）：无界是刻意的；安全阀是指标 + WARN，不是限流。
- **不改任何协议适配器**：`CustomTcpAdapter` / `ModbusTcpAdapter` / `MqttAdapter` / `OpcUaAdapter` 一行不动。
- **不改 `InputPointPropagator` 的任何行为**（含"通道不存在/未连接就跳过"——它是无界队列不失控的前提）。
- **传播失败不得连累 OUTPUT 值的落库**：`changeGate.filter` 已推进变更基线，这一轮的值丢了就永久丢（Redis/历史/推送三处都不会再见到）。
- **构建命令**：只跑某模块必须带 `-am`（`mvn test -pl sdncustom-server -am`），否则会用 `~/.m2` 里的旧依赖产物编译；按类过滤再加 `-Dsurefire.failIfNoSpecifiedTests=false`。拿不准就 `mvn clean test`。
- 测试计数会变：完成后需同步 `CLAUDE.md` 里的 `@Test` 总数与分模块计数（当前 common 25 / protocol 47 / server 199）。
- 中文注释/文档，与仓库现有风格一致；提交信息用 Conventional Commits + 中文摘要。

---

### Task 1: 抽出 `LiveSourceChecker`（存活来源过滤）

存活来源过滤现在只存在于 `AcquisitionEngine.onlyLiveSources`（`AcquisitionEngine.java:198`），但 Task 2 的阶段线程也要用它做"写出后再复核"。先抽成独立单元，行为一字不改。

**Files:**
- Create: `sdncustom-server/src/main/java/com/sdncustom/server/service/LiveSourceChecker.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/AcquisitionEngine.java`（删私有方法 `onlyLiveSources`，改为委托；**新增**字段）
- Modify: `sdncustom-server/src/test/java/com/sdncustom/server/service/AcquisitionEngineTest.java`（加 `LiveSourceChecker` 的 mock 与放行桩）
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/LiveSourceCheckerTest.java`

**Interfaces:**
- Consumes: `com.sdncustom.server.repository.ChannelRepository#findByStatus(ChannelStatus)`
- Produces: `LiveSourceChecker#onlyLive(List<PointValue>) : List<PointValue>`（Spring `@Component`，供 Task 2、Task 3 注入）

- [ ] **Step 1: 写失败测试**

创建 `LiveSourceCheckerTest.java`：

```java
package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 存活来源过滤：通道断开时 markPointsCommLost 会推 COMM_LOST，
 * 若迟到的 GOOD 值随后再落库/推送，客户端会被刷回正常——按 DB 状态过滤。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveSourceChecker 存活来源过滤 测试")
class LiveSourceCheckerTest {

    @Mock
    private ChannelRepository channelRepository;

    private LiveSourceChecker checker;

    @BeforeEach
    void setUp() {
        checker = new LiveSourceChecker(channelRepository);
    }

    private static PointValue value(String pointId, String sourceChannelId) {
        PointValue pv = new PointValue();
        pv.setPointId(pointId);
        pv.setSourceChannelId(sourceChannelId);
        return pv;
    }

    private static Channel connected(String channelId) {
        Channel ch = new Channel();
        ch.setChannelId(channelId);
        ch.setStatus(ChannelStatus.CONNECTED);
        return ch;
    }

    @Test
    @DisplayName("来源通道已断开的值被丢弃")
    void dropsValuesFromDisconnectedChannels() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED))
                .thenReturn(List.of(connected("ch_live")));

        List<PointValue> result = checker.onlyLive(
                List.of(value("p_live", "ch_live"), value("p_dead", "ch_dead")));

        assertEquals(List.of("p_live"), result.stream().map(PointValue::getPointId).toList());
    }

    @Test
    @DisplayName("sourceChannelId 为空的值保留（历史数据可能没有来源通道）")
    void keepsValuesWithoutSourceChannel() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of());

        List<PointValue> result = checker.onlyLive(List.of(value("p_null", null)));

        assertEquals(1, result.size());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=LiveSourceCheckerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class LiveSourceChecker`

- [ ] **Step 3: 实现 `LiveSourceChecker`**

创建 `LiveSourceChecker.java`：

```java
package com.sdncustom.server.service;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.ChannelStatus;
import com.sdncustom.server.repository.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 丢弃来源通道已不在 CONNECTED 的迟到值。通道断开时 {@code markPointsCommLost} 会推 COMM_LOST，
 * 若这些迟到值随后再推送，客户端会被刷回 GOOD——按 DB 状态（客户端看到的状态投影）过滤。
 *
 * <p>独立成单元是因为它有两个调用点、时序不同：采集线程在提交前用一次（滤掉读取期间的断开），
 * 传播阶段在设备写出**之后**再用一次（写出期间用户可能断开，窗口只剩一次查询的距离）。
 */
@Component
@RequiredArgsConstructor
public class LiveSourceChecker {

    private final ChannelRepository channelRepository;

    public List<PointValue> onlyLive(List<PointValue> values) {
        Set<String> live = channelRepository.findByStatus(ChannelStatus.CONNECTED).stream()
                .map(Channel::getChannelId)
                .collect(Collectors.toSet());
        return values.stream()
                .filter(pv -> pv.getSourceChannelId() == null || live.contains(pv.getSourceChannelId()))
                .toList();
    }
}
```

- [ ] **Step 4: 让 `AcquisitionEngine` 委托给它**

在 `AcquisitionEngine.java` 中：

1. 字段区（`:37-50` 一带）**新增**一行，**不要删 `channelRepository`**——`acquire()` 开头仍用它
   `findByStatus(ChannelStatus.CONNECTED)` 取通道列表，删了就编译不过：

```java
    private final LiveSourceChecker liveSourceChecker;
```

2. 删除私有方法 `onlyLiveSources`（`:198-205` 的整段 javadoc + 方法）。
3. 把 `acquire()` 里对它的调用（`:113`）改为：

```java
                List<PointValue> publishable = liveSourceChecker.onlyLive(changedValues);
```

4. 清理因此不再使用的 import：`Channel`、`ChannelStatus`、`Collectors`、`Set`（若别处不再用）。
   注意 `pointRepository` 仍需 `ChannelStatus`？——**不需要**，`findByChannelIdAndDirection` 用的是 `PointDirection`。以编译器报错为准逐个删。

> 此时 `acquire()` 的其余部分（含 `propagateSafely` 与两次直接提交）**保持原样**，因为 `AcquisitionEngineTest` 里依赖它们的用例要到 Task 3 才迁移。

5. 同步 `AcquisitionEngineTest`：`@InjectMocks` 会因新字段而注入 null，不补 mock 会在 `acquire()` 里 NPE。在字段区新增

```java
    @Mock
    private LiveSourceChecker liveSourceChecker;
```

（`@Mock ChannelRepository channelRepository` **保留**——`acquire()` 开头仍调它）并在 `setUp()` 末尾加一行放行桩（过滤逻辑本身由 `LiveSourceCheckerTest` 覆盖）：

```java
        lenient().when(liveSourceChecker.onlyLive(anyList())).thenAnswer(inv -> inv.getArgument(0));
```

- [ ] **Step 5: 运行相关测试确认全绿**

Run: `mvn test -pl sdncustom-server -am -Dtest='LiveSourceCheckerTest,AcquisitionEngineTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 两个类全绿（`AcquisitionEngineTest` 原有 8 个用例不因本次抽取而改变行为）

- [ ] **Step 6: 提交**

```bash
git add sdncustom-server/src/main/java/com/sdncustom/server/service/LiveSourceChecker.java \
        sdncustom-server/src/main/java/com/sdncustom/server/service/AcquisitionEngine.java \
        sdncustom-server/src/test/java/com/sdncustom/server/service/LiveSourceCheckerTest.java \
        sdncustom-server/src/test/java/com/sdncustom/server/service/AcquisitionEngineTest.java
git commit -m "refactor(server): 抽存活来源过滤为 LiveSourceChecker（传播阶段也要用）"
```

---

### Task 2: 新增 `PropagationService`（传播阶段）

这是本次改动的核心：设备写出 + 合并批次提交落库/推送，全部在一个后台单线程上完成。

**Files:**
- Create: `sdncustom-server/src/main/java/com/sdncustom/server/service/PropagationService.java`
- Modify: `sdncustom-server/src/main/resources/application.yml`（`sdncustom:` 段内新增 `propagation:` 节）
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/PropagationServiceTest.java`

**Interfaces:**
- Consumes: `InputPointPropagator#propagate(List<PointValue>) : List<PointValue>`、`PersistenceService#submitBatch(List<PointValue>) : void`、`DistributionService#pushBatch(List<PointValue>) : void`、`LiveSourceChecker#onlyLive(List<PointValue>) : List<PointValue>`
- Produces: `PropagationService#submitBatch(List<PointValue>) : void`（供 Task 3 调用）；配置键 `sdncustom.propagation.queue-warn-depth`、`sdncustom.propagation.drain-timeout-seconds`；指标 `sdncustom.propagation.queue.depth` / `.batch.seconds` / `.batch.errors`

- [ ] **Step 1: 写失败测试**

创建 `PropagationServiceTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=PropagationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `cannot find symbol: class PropagationService`

- [ ] **Step 3: 实现 `PropagationService`**

创建 `PropagationService.java`：

```java
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
                log.warn("Propagation queue not drained in {}s, dropping {} batch(es) — 这些写命令不会送达设备",
                        drainTimeoutSeconds, propagationExecutor.getQueue().size());
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

    /** 供测试断言"积压告警已触发" */
    boolean backlogWarningActive() {
        return backlogWarned;
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
            // 异常逃出后台线程会让后续批次再也不被处理（静默停写）
            batchErrors.increment();
            log.error("Propagation batch failed, {} output value(s) dropped from this batch",
                    outputChanges.size(), e);
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl sdncustom-server -am -Dtest=PropagationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 8 个用例全绿

- [ ] **Step 5: 加配置项**

在 `application.yml` 的 `sdncustom:` 段内、`persistence:` 节之后插入：

```yaml
  # 传播（设备写出）后台阶段
  propagation:
    # 队列无界（写命令不丢）；深度超过此值打一次 WARN，让无界增长的 OOM 风险在发生之前可见
    queue-warn-depth: 100
    # 停机排空窗口（秒）；超时则强杀并丢弃剩余批次——这是唯一会丢写命令的路径
    drain-timeout-seconds: ${SDNCUSTOM_PROPAGATION_DRAIN_S:5}
```

- [ ] **Step 6: 提交**

```bash
git add sdncustom-server/src/main/java/com/sdncustom/server/service/PropagationService.java \
        sdncustom-server/src/main/resources/application.yml \
        sdncustom-server/src/test/java/com/sdncustom/server/service/PropagationServiceTest.java
git commit -m "feat(server): 新增 PropagationService，设备写出移出采集线程"
```

---

### Task 3: `AcquisitionEngine` 改走传播阶段

采集线程从"自己传播 + 自己提交落库/推送"变成"只提交给传播阶段"。

**Files:**
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/AcquisitionEngine.java`
- Modify: `sdncustom-server/src/main/java/com/sdncustom/server/service/PersistenceService.java`（仅类注释，Step 3.5）
- Test: `sdncustom-server/src/test/java/com/sdncustom/server/service/AcquisitionEngineTest.java`

**Interfaces:**
- Consumes: `PropagationService#submitBatch(List<PointValue>)`（Task 2）、`LiveSourceChecker#onlyLive(List<PointValue>)`（Task 1）
- Produces: 无（本任务后 `acquire()` 不再直接依赖 `PersistenceService` / `DistributionService` / `InputPointPropagator`）

- [ ] **Step 1: 先改测试（红）**

编辑 `AcquisitionEngineTest.java`：

1. 字段区：删除

```java
    @Mock
    private PersistenceService persistenceService;

    @Mock
    private DistributionService distributionService;

    @Mock
    private InputPointPropagator inputPointPropagator;
```

新增（`@Mock LiveSourceChecker liveSourceChecker` 与它的放行桩在 Task 1 已加，**不要重复添加**）：

```java
    @Mock
    private PropagationService propagationService;
```

2. 删除这三个用例（它们的职责已迁到 `PropagationServiceTest`：`propagationValuesJoinTheSameBatch` → `mergesInputValuesIntoTheSameBatch`；`propagationFailureDoesNotLoseTheCycle` → `propagationFailureDoesNotLoseOutputValues`；`inputValuesBypassLivenessFilter` → `inputValuesBypassLivenessFilter`），以及 `noChangesMeansNoPropagation`（与 `noChangesMeansNoWrites` 合并后语义重复）：

```
propagationValuesJoinTheSameBatch
propagationFailureDoesNotLoseTheCycle
inputValuesBypassLivenessFilter
noChangesMeansNoPropagation
```

3. 三个保留用例的断言改为面向 `propagationService`：

`noConnectedChannels` 与 `disconnectedAdapterTriggersChannelDisconnect` 里：

```java
        verifyNoInteractions(propagationService);
```

`changedValuesFlushedInBatch` 里：

```java
        verify(propagationService).submitBatch(read);
        assertEquals(1.0, meterRegistry.get("sdncustom.acquisition.cycle").timer().count());
        assertEquals(1.0, meterRegistry.get("sdncustom.acquisition.changed.values").counter().count());
```

`noChangesMeansNoWrites` 里：

```java
        verifyNoInteractions(propagationService);
```

4. 新增一个用例，钉住"第一道存活过滤仍在采集线程执行"：

```java
    @Test
    @DisplayName("存活过滤仍在采集线程执行：提交给传播阶段的是过滤后的值")
    void livenessFilterRunsBeforeSubmit() {
        when(channelRepository.findByStatus(ChannelStatus.CONNECTED)).thenReturn(List.of(channel));
        when(pointRepository.findByChannelIdAndDirection("ch_001", PointDirection.OUTPUT)).thenReturn(List.of(point));
        ProtocolAdapter adapter = mock(ProtocolAdapter.class);
        when(protocolRegistry.getOrCreate(channel)).thenReturn(adapter);
        when(adapter.isConnected()).thenReturn(true);
        List<PointValue> read = List.of(value("p1", 25.0));
        when(adapter.readPoints(anyList())).thenReturn(read);
        when(changeGate.filter(read, java.util.Map.of("p1", point))).thenReturn(read);
        PointValue alive = value("p1", 25.0);
        when(liveSourceChecker.onlyLive(read)).thenReturn(List.of(alive));

        engine.acquire();

        verify(propagationService).submitBatch(List.of(alive));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl sdncustom-server -am -Dtest=AcquisitionEngineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— 找不到 `propagationService` 字段对应的生产代码依赖（`AcquisitionEngine` 还没有该构造参数）

- [ ] **Step 3: 改 `AcquisitionEngine`**

1. 字段区：删除 `persistenceService`、`distributionService`、`inputPointPropagator` 三个 `private final` 字段，新增

```java
    private final PropagationService propagationService;
```

（`liveSourceChecker` 在 Task 1 已加入。）

2. `acquire()` 里从"提交落库 + 复核 + 推送"（原 `:109-146` 一带）整段替换为：

```java
            // 本轮无有效变化则零提交，避免重复数据打爆存储与推送通道
            if (!changedValues.isEmpty()) {
                // 读取期间用户可能已断开通道：断开来源的迟到值不写缓存/历史、也不下发到设备
                List<PointValue> publishable = liveSourceChecker.onlyLive(changedValues);
                if (!publishable.isEmpty()) {
                    meterRegistry.counter("sdncustom.acquisition.changed.values")
                            .increment(publishable.size());

                    // 传播（设备写出）与落库/推送都在传播阶段的后台线程上完成：
                    // 采集线程只提交快照、绝不等设备写出，acquire() 的耗时不再受慢设备影响。
                    // 顺序保证：进 PersistenceService 的生产者只有 PropagationService 一个，
                    // 且 INPUT 值与其来源 OUTPUT 值在该阶段被合并成同一批。
                    propagationService.submitBatch(publishable);
                }
            }
```

3. 删除私有方法 `propagateSafely`（`:184-192`）——它的职责已由 `PropagationService.propagateSafely` 承担。
4. 清理不再使用的 import：`PersistenceService`/`DistributionService` 无 import（同包）；确认 `ArrayList`、`Set`、`Collectors`、`Channel`、`ChannelStatus` 是否仍被用到（`Channel`/`ChannelStatus` 用于 `findByStatus`，保留；`Collectors`、`Set` 若仅存活过滤用过则删）。
5. **改 `PersistenceService` 的类注释**（spec §9 列出的文件，代码本身不动）：把"采集调度线程只提交快照，绝不等一次 Redis 往返……"那段里的生产者描述改为"生产者只有 `PropagationService` 一个（采集线程不再直接提交），所以单线程 + FIFO 仍是批次间保序的唯一手段"。注释里关于 `acquire()` 的 1:1 耗时说明保留——它解释了为什么要有这个队列。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl sdncustom-server -am -Dtest='AcquisitionEngineTest,PropagationServiceTest,LiveSourceCheckerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 三个类全绿

- [ ] **Step 5: 全量回归**

Run: `mvn clean test`
Expected: BUILD SUCCESS，server 模块用例数 = 199 - 4（删掉的）+ 1（新增的 `livenessFilterRunsBeforeSubmit`）+ 2（`LiveSourceCheckerTest`）+ 8（`PropagationServiceTest`）= 206

- [ ] **Step 6: 提交**

```bash
git add sdncustom-server/src/main/java/com/sdncustom/server/service/AcquisitionEngine.java \
        sdncustom-server/src/main/java/com/sdncustom/server/service/PersistenceService.java \
        sdncustom-server/src/test/java/com/sdncustom/server/service/AcquisitionEngineTest.java
git commit -m "refactor(server): 采集周期只提交传播阶段，不再同步做设备写出"
```

---

### Task 4: 文档同步

代码里的契约变了，三份文档必须跟着改，否则下一轮读文档的人会按旧模型推理。

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/tcp-pipeline.md`
- Modify: `docs/backlog.md`

**Interfaces:**
- Consumes: Task 2/3 落地后的实际行为（指标名、配置键、调用链）
- Produces: 无代码产出

- [ ] **Step 1: 更新 `CLAUDE.md`**

1. **数据流图**里 `AcquisitionEngine → ChangeGate → InputPointPropagator` 那一段，改为经由传播阶段：

```
外部系统 ──→ Channel(读关联的 OUTPUT 测点) ──→ AcquisitionEngine ──→ ChangeGate(死区/变更过滤)
                                                    ↓ 仅有效变化（批量，只提交快照）
                              PropagationService(单线程 FIFO，无界队列)
                              ├─ InputPointPropagator（写到 INPUT 测点的关联通道）
                              └─ 合并 [OUTPUT + INPUT] 成一批
                       ┌────────────────────────────────┴────────────────────────────────┐
        PersistenceService(单线程 FIFO)                                    DistributionService(单线程队列)
        Redis MSET(实时) + TDengine 多表INSERT(历史)                        WebSocket 按通道合帧 ──→ 前端
```

2. **`AcquisitionEngine` 条目**：把"并调用 `InputPointPropagator` 把有效变化写到 INPUT 测点——传播值与输出测点值**并入同一批次**；该批次**异步移交** `PersistenceService` 队列落库、交给 `DistributionService` 队列推送"改为"把有效变化**提交给 `PropagationService`**（只提交快照、立即返回）；设备写出、INPUT 值合并、落库与推送都在该阶段的后台线程上完成——`acquire()` 的耗时只由读取 + 过滤 + 入队构成"。
   同一条里还有一处**方法改名**要跟着改：推送前那道复核现在写的是 `onlyLiveSources`（Task 1 已把它抽成 `LiveSourceChecker.onlyLive`，该复核在 Task 2/3 后改由传播阶段在设备写出之后执行）——统一改成 `LiveSourceChecker.onlyLive`，并写明它现在的执行位置。

3. **新增 `PropagationService` 条目**（放在 `PersistenceService` 之前）：

```
- **PropagationService**：传播阶段后台队列。`submitBatch` 只入队立即返回；工作线程做 InputPointPropagator
  的设备写出，再把 `[OUTPUT + INPUT]` 合并成**同一批**提交落库与推送。**线程数恒为 1，且是 PersistenceService
  的唯一生产者**——这是"INPUT 传播值不晚于下一轮 OUTPUT 值落库"的新保证方式（旧方式是两者同批）。队列**无界**
  （写命令不是遥测，不丢）：只有"通道已连接但写得慢"才积压，通道未连接时 propagator 直接跳过不入队；
  深度超 `sdncustom.propagation.queue-warn-depth`（默认 100）打 WARN。停机给
  `sdncustom.propagation.drain-timeout-seconds`（默认 5s）排空窗口，**超时丢弃是唯一会丢写命令的路径**，会点名批次数
```

4. **`PersistenceService` 条目**：把"采集调度线程只 `submitBatch` 快照"改为"生产者只有 `PropagationService` 一个（采集线程不再直接提交）"。

5. **指标清单**：新增三条 `propagation.queue.depth`（Gauge）、`propagation.batch.seconds`（Timer）、`propagation.batch.errors`（Counter）；`acquisition.changed.values` 行文不变。并注明两处语义变化：
   - `acquisition.cycle` **不再包含设备写出**（历史 P99 不可直接比较）
   - 旧 `sdncustom.propagation.errors`（原 `AcquisitionEngine.propagateSafely` 的）**已由 `propagation.batch.errors` 接替**，不要再按旧名字找

6. **测试计数**：更新为实际值（Task 3 Step 5 的 `mvn clean test` 输出为准；预计 common 25 / protocol 47 / server 206，总数 278）。

- [ ] **Step 2: 更新 `docs/tcp-pipeline.md`**

该文档按行号描述 as-is 链路，改动后行号全变。至少改这两处：

1. §阶段 2 的标题与首句：`## 阶段 2：传播（**同步跑在采集线程上**）` → `## 阶段 2：传播（**在 PropagationService 的后台线程上**）`，并把"同步跑在采集线程上"的措辞与 backlog A9 的引用一并更新。
2. 阶段 2 里的行号引用（`InputPointPropagator.java:41` / `writeToChannel:79` / `adapter.writePoint:98` / `toInputValue:115`）在 `InputPointPropagator` **未被本次改动**的前提下仍然有效——**逐个核对**后再改（该文件本次一行未动，所以只需核对不必改）；真正要改的是"谁调用它"的上下文。
3. 在阶段 2 末尾补一句：调用方是 `PropagationService.process`（线程 `propagation`），采集线程只 `submitBatch`。
4. `docs/tcp-pipeline.md:58` 这一步里写的 `onlyLiveSources` 改为 `LiveSourceChecker.onlyLive`（Task 1 抽出的新单元），并顺带修正该行引用的行号（`acquire():111-139` 在 Task 3 后已变）。

- [ ] **Step 3: 更新 `docs/backlog.md`**

1. **A9 段落**改为已处理，写明：搬到了 `PropagationService`（单线程 + 无界队列 + 合并批次），`acquisition.cycle` 不再含设备写出；并保留它记录的历史读数作为背景。
2. **测试缺口一节补一条**：`PropagationService` 的队列是**无界**的（决策：写命令不丢），失败模式是 OOM 而非丢数据；安全阀是深度指标 + WARN。若将来现场出现"设备长期慢写"，需要回到"有界 + 合并"。

- [ ] **Step 4: 提交**

```bash
git add CLAUDE.md docs/tcp-pipeline.md docs/backlog.md
git commit -m "docs: 同步传播异步化后的调用链、指标与 A9 状态"
```

---

## 完成标准

- `mvn clean test` 全绿（预计 278 个 `@Test`）
- `acquire()` 的代码里不再出现 `InputPointPropagator` / `PersistenceService` / `DistributionService`
- `grep -rn "propagateSafely" sdncustom-server/src/main` 只命中 `PropagationService` 内的私有方法
- 三份文档与代码一致（CLAUDE.md 的调用链与指标、tcp-pipeline 的阶段 2、backlog 的 A9 状态）
