# 传播异步化：把设备写出移出采集线程

> 2026-09-20。对应 `docs/backlog.md` 的 **A9**（传播在采集线程上做无界设备 I/O）。
> 当前链路见 `docs/tcp-pipeline.md` §阶段 2——那份描述 as-is，本 spec 描述 to-be。
>
> 本 spec 只做"把传播搬出去"这一件事。同通道读写争用的**根治**由另外两份工作承担
> （Modbus 按事务 ID 放开在途、自定义 TCP 帧格式 v2 的 RequestId 多路复用），见 §12。

## 1. 背景

`AcquisitionEngine.acquire()`（`:71`）是 `@Scheduled(fixedDelay)`，跑在采集调度线程上。它今天的收尾是：

1. 各通道并行读取（8 线程池，`allOf` 限时 5s），只消费**已完成**的 future（`:96-111`）
2. `onlyLiveSources(changedValues)`（`:113`）滤掉读期间已断开来源的迟到值
3. `propagateSafely(publishable)`（`:119`）→ `InputPointPropagator.propagate`（`:41`）：**对每个 INPUT 测点做一次真实的设备写出**
4. 把 `[OUTPUT + INPUT]` 合并成一批，`persistenceService.submitBatch`（`:127`）+ `distributionService.pushBatch`（`:137`）

第 3 步是同步的设备 I/O，最坏一个写超时（`CustomTcpAdapter.READ_TIMEOUT_MS = 5000`）。它不在
`allOf` 的限时里，于是**一台写超时的设备会把整轮采集拖长**，而这是所有通道共用的周期。

`PersistenceService` 的类注释里已经写明这层区分：'`acquire()` 是 `@Scheduled(fixedDelay)`，
写在它里面的耗时会 1:1 吃掉采集频率'。落库与推送早已按这条原则搬走，**只剩传播没搬**。

另外，`sdncustom.acquisition.cycle` 这个 Timer 因此混入了设备写出耗时，P99 不能反映采集本身是否健康。

## 2. 目标与非目标

**目标**

- 采集调度线程不再执行任何设备写出；`acquire()` 的耗时只由"读取 + 过滤 + 入队"构成
  （**残留**：同时挂两种方向的通道，其读仍可能与传播线程的写争适配器锁而超时——见 §11.5）
- 落库与推送的**顺序语义不变**：INPUT 传播值仍与其来源 OUTPUT 值同批，且不晚于下一轮 OUTPUT 值落库
- 传播失败、慢通道、队列积压都可观测

**非目标**

- 不改任何协议适配器（`CustomTcpAdapter` / `ModbusTcpAdapter` / `MqttAdapter` / `OpcUaAdapter` 一行不动）
- 不改"值语义是意图"（写失败仍用 OUTPUT 的值/质量/时间戳更新 INPUT，不降质量）
- 不改 `InputPointPropagator` 的跳过规则（通道不存在/未连接就跳过）
- 不解决同通道读写争用——那是 §12 两项工作的事
- 不做传播的并发化（多线程）——单线程 FIFO 是保序前提

## 3. 决策记录

| 决策 | 取舍 |
|---|---|
| **新开一个传播阶段，且 OUTPUT 批次也从这个阶段过**（方案 A） | 保证进 `PersistenceService` 的生产者**唯一**，批次序与今天完全等价。代价是落库/推送延迟被设备写出推迟（最坏每批 +5s），但采集周期不受影响 |
| 否：传播独立线程 + 采集线程仍直接提交 OUTPUT 批次（方案 B） | 落库延迟不受设备写影响，但慢写下 `INPUT(N)` 可能晚于 `OUTPUT(N+1)` 提交，**违反 CLAUDE.md 写明的顺序不变量** |
| 否：按通道分片并发传播 + 序合流点（方案 C） | 慢通道不拖累其他通道，但复杂度明显更高；多台慢设备同时出现前没有收益（YAGNI） |
| **无界队列**，不丢写命令 | 写命令不是遥测，丢弃语义更重。代价是失败模式从"丢数据"变成 OOM，见 §6 的安全阀与 §11 的风险 |
| 否：有界 + 丢最旧（与落库/推送同策略） | 被丢的是真实写命令，设备永远收不到那个设定值 |
| 否：按 INPUT 测点合并（只保留最新意图） | 能天然限界且不丢"最终意图"，但会静默吞掉中间设定值（斜坡类控制会受影响），本轮不做 |
| 否：把传播塞进 `PersistenceService` 的线程 | 会让设备 I/O 延迟**所有**通道的落库，违背该服务"快速落库"的契约 |
| 不改适配器 | 同通道争用交给 §12；本轮只搬线程模型，不扩大改动面 |

## 4. 设计：`PropagationService`

新增 `com.sdncustom.server.service.PropagationService`，形态与 `PersistenceService` 同构：

```
单线程 ThreadPoolExecutor（线程名 "propagation"）
  + LinkedBlockingQueue（无界，容量 Integer.MAX_VALUE）
  + @PostConstruct 注册指标
  + @PreDestroy 排空（见 §7）
```

```java
/** 采集线程调用：只入队，立即返回 */
public void submitBatch(List<PointValue> outputChanges)
```

工作线程每批执行：

1. `inputPointPropagator.propagate(batch)` → `List<PointValue>`（INPUT 的新值；内部完成全部设备写出）
2. `persistenceService.submitBatch(merge(batch, inputValues))` —— **合并成一批**
3. `distributionService.pushBatch(pushable)` —— 推送前的复核见 §5

`merge` 保留 `batch`（OUTPUT 值）在前、`inputValues` 在后，与今天 `AcquisitionEngine` 里
`allValues = publishable + inputValues` 的顺序一致（`DistributionService` 按通道合帧，顺序影响很小，
但保持一致以免行为漂移）。

工作线程必须把**每批**包在 `try { ... } catch (Exception e)` 里：`log.error` + 计
`sdncustom.propagation.batch.errors`，然后继续处理下一批。异常逃出后台线程会让**后续批次再也不被处理**
（静默停写），与 `PersistenceService.store` 同理。

**传播调用本身还要再包一层**（等价于今天 `AcquisitionEngine.propagateSafely` 的职责，`:184-192`）：
`propagate` 抛异常时返回空列表、只损失 INPUT 值，**OUTPUT 值必须照常落库**——`changeGate.filter`
已经推进过变更基线，丢了就永久丢（Redis/历史/推送三处都不会再见到）。**不要把 `propagate` 与
`persistenceService.submitBatch` 放在同一个 try 里**，否则传播一失败就把 OUTPUT 值一起丢了。
`propagate` 失败计的是 `batch.errors`（原 `propagation.errors` 由它接替）。

## 5. 数据流与顺序保证

```
采集调度线程:  changedValues → onlyLiveSources → propagationService.submitBatch(publishable)   ← 到此为止
                                                            ↓（单线程 FIFO）
propagation 线程:  ① propagate（设备写出）→ ② persistenceService.submitBatch(OUTPUT+INPUT)
                                        → ③ 复核 → distributionService.pushBatch(...)
```

**顺序**：进 `PersistenceService`（单线程 FIFO）的只有 `PropagationService` 一个生产者，
所以"批次按提交序落库"这一性质不变；又因为 INPUT 与其 OUTPUT 在**同一批**里，今天
"INPUT 传播值不会晚于下一轮 OUTPUT 值落库"的保证**原样成立**，不需要额外机制。

**两道 `onlyLiveSources` 复核**（`:113` 与 `:134`）保留，位置调整：

- 第一道留在采集线程（提交前），语义不变
- 第二道移到 propagation 线程内、**紧跟设备写出之后**。今天它离写出隔了"两次入队"，移动后窗口更小
- INPUT 值仍**不过**第二道滤网（决策 A：INPUT 的来源通道是写出目标，掉线只代表没送达）

`propagateSafely`（`:184`）随之删除：它的职责（传播异常不拖垮本轮）由阶段边界承担。

## 6. 队列与背压

- 队列**无界**（用户决策）。**保留 `InputPointPropagator.writeToChannel` 现有的"通道未连接即跳过"规则**——
  这是把无界增长限制在"**已连接但写得慢**"这一种场景的关键：设备掉线时写会失败并置 `connected=false`，
  后续写被直接跳过，队列立刻停止增长。否则设备掉线一小时、恢复后会把一小时的陈旧设定值全灌给设备。
- 安全阀：`sdncustom.propagation.queue-warn-depth`（默认 100 批，**连字符**，见 §9 与 `application.yml`）——队列深度超过它时打一次 WARN
  （同一积压期间不重复刷屏），并暴露 `sdncustom.propagation.queue.depth` Gauge。
  无界队列的 OOM 必须**在 OOM 之前可见**。

## 7. 停机

因策略是"不丢"，`@PreDestroy` 不能照抄 `DistributionService` 的直接 `shutdownNow`：

1. `shutdown()` 停止接收新批次
2. 等待 `sdncustom.propagation.drain-timeout-seconds`（默认 **5**，比 `PersistenceService` 的 2s 长——
   写出是设备 I/O）
3. 超时则 WARN 并点名剩余批次数，再 `shutdownNow()`

**这是本设计里唯一会丢写命令的路径**，所以第 3 步的日志必须有，且要能看出丢了多少批。

## 8. 可观测性

| 指标 | 变化 |
|---|---|
| `sdncustom.acquisition.cycle` | **语义变化**：不再包含设备写出**自身**的耗时（采集线程不写）。历史 P99 不可直接比较，"写入耗时"另见下一行。**残留**：同通道读写争锁仍会把该通道的读顶出 5s 窗口，故 P99 并非"纯读取"——见 §11.5 |
| `sdncustom.propagation.batch.seconds` | 新增 Timer：每批"写出 + 提交"的耗时，量化设备写出占比 |
| `sdncustom.propagation.queue.depth` | 新增 Gauge：传播队列积压批次数 |
| `sdncustom.propagation.batch.errors` | 新增 Counter：批次级兜底异常数（**非 0 说明有批次被整批放弃**，需查日志）。**接替**原 `AcquisitionEngine.propagateSafely` 计数的 `sdncustom.propagation.errors`——后者随搬移消失，排障时不要按旧名字找 |
| `sdncustom.propagation.writes` / `.failures` | 不变，但由 propagation 线程而非采集线程计数（tag=channel 不变） |
| `sdncustom.acquisition.changed.values` | 不变（仍在采集线程计数） |

## 9. 受影响的文件清单

| 文件 | 改动 |
|---|---|
| `service/PropagationService.java` | **新增**（§4） |
| `service/AcquisitionEngine.java` | 删 `inputPointPropagator` 字段、`propagateSafely`；`acquire()` 收尾只到 `submitBatch`；第二道复核移出 |
| `service/PersistenceService.java` | 仅类注释：生产者改为唯一（`PropagationService`），不再由 `AcquisitionEngine` 直接提交 |
| `service/DistributionService.java` | 无接口改动 |
| `resources/application.yml` | 新增两个配置项（§6/§7） |
| `CLAUDE.md` | `AcquisitionEngine` / `PersistenceService` 两条、数据流图、指标清单、"关键服务"段 |
| `docs/tcp-pipeline.md` | §阶段 2 改为异步；阶段编号与行号重算 |
| `docs/backlog.md` | A9 标记已处理（含"搬走后仍存在的同通道争用交给 §12 两项工作"） |

## 10. 测试计划

**新增 `PropagationServiceTest`**

1. OUTPUT 与 INPUT **同批**提交给 `PersistenceService`（用 ArgumentCaptor 断言批内容与顺序）
2. `submitBatch` **立即返回**：用一个会阻塞（CountDownLatch）的假适配器，断言调用线程未被阻塞
3. 两批的提交顺序 = 入队顺序（FIFO）
4. 队列深度 Gauge 反映积压；超过 warn-depth 时打 WARN（不重复刷屏）
5. 传播异常不影响后续批次被处理
6. 停机排空：队列非空时 `@PreDestroy` 会等在途批次完成

**改造 `AcquisitionEngineTest`**

7. `acquire()` 不再调用 `inputPointPropagator`，而是调用 `propagationService.submitBatch`（且传入过滤后的值）
8. 无有效变化时零提交（沿用现有断言）

## 11. 已知取舍与风险

1. **落库/推送延迟变大**：慢设备最坏每批 +5s。只影响新鲜度，不影响正确性；是异步化的直接代价。
2. **无界队列的失败模式是 OOM**，不是丢数据。§6 的"未连接即跳过"把风险压到"设备一直连着、只是慢，
   且长期写入速率高于写出速率"，但**不归零**。若不能接受，需回到"有界 + 合并"（本轮已明确否掉）。
3. **停机排空超时会丢写命令**（§7），必须靠日志可见。
4. **写出与 disconnect 的竞态**：`disconnect()` 不持锁、会 shutdown socket 打断写出，
   写会抛异常并被记进 `propagation.failures`。语义可接受（本来就是"未送达"），
   但**排障时会看到一批正常发生的失败计数**，需要知道它不一定是故障。
5. **同通道读写争用依然存在，而且仍能拖长采集周期**：本轮不碰。读与写在同一条通道上共用适配器的那把锁，
   且锁覆盖**整个往返**（`CustomTcpAdapter.java:37/123/151`、`ModbusTcpClient.java:29/221`）——
   搬到传播线程之前，传播在调度线程上跑且发生在 `allOf` 返回**之后**，同通道的读与写原本不会重叠；
   搬走之后传播线程在写出时，8 线程池正在读同一个适配器，**一次卡住的写（最长 `READ_TIMEOUT` 5s）
   可以把该通道的读顶出 `acquire()` 的 5s 窗口**。不丢数据（该通道这一轮的 future 被直接丢弃、
   `ChangeGate` 基线未推进，下一轮读到同一个值仍算变化、照常发出），但 `acquisition.cycle` 里
   **仍含有设备写出的分量**——只对"同时挂 OUTPUT 与 INPUT"的通道成立（这是合法配置：
   自引用禁令只挡"引用成环"，不挡"一个通道两种方向"）。
   - **没有 INPUT 测点的通道是完全解耦的**：传播线程根本不碰它们的适配器，读不会与写争锁。
   - 采集调度线程**自身**仍不执行任何设备 I/O；上面这条残留是"读在等同通道的写"，不是"采集线程在写"。
   - 真正消除见 §12。
6. `acquisition.cycle` 的历史数据不可比（§8），告警阈值若依赖它需同步调整。

## 12. 与后续两项工作的关系

本 spec 落地后，同通道读写仍由 `CustomTcpAdapter` / `ModbusTcpClient` 各自的那把锁串行化，
异步化改变的只是**谁在等**（从"调度线程必须等完所有写"变成"该通道的读可能在锁上撞到一次在飞的写"，
见 §11.5——**不是**把写出耗时从采集周期里彻底移走）。**根治**由以下两项承担（各自独立 spec）：

- **Modbus**：`ModbusTcpClient` 已有 `transactionId`（`:25`）与响应校验（`:233-243`），
  把"单一在途"放开为按事务 ID 配对的多路复用即可，带配置开关以便个别不支持多未完成请求的设备退回
- **自定义 TCP**：帧格式 v2 加 2 字节 `RequestId`（`2026-09-20-tcp-push-heartbeat-design.md:57`，
  `pending.put(id, future)` 见 `:109`）
- **OPC-UA 不做**：`OpcUaAdapter` 无锁，Milo 客户端已在会话层做请求关联与多路复用

## 13. 实施顺序建议

1. `PropagationService` + 单测（§10 的 1-6）
2. `AcquisitionEngine` 改造 + 测试改造（§10 的 7-8）
3. 配置项、指标、文档
4. 全量 `mvn clean test`（`-pl` 时必须带 `-am`，见 CLAUDE.md 的症状小节）
5. 人工验证：连一条真实（或 mock）通道并触发传播，观察 `propagation.batch.seconds` 与
   `acquisition.cycle` 已经分离——写出耗时只体现在前者。**不需要**专门造慢设备：把
   `CustomTcpAdapter.READ_TIMEOUT_MS` 临时调大、或让 mock 通道指向一个只接受连接不响应的端口，
   即可看到 `acquisition.cycle` 不再跟着写超时走
