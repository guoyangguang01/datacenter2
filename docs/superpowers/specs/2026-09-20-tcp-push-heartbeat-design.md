# 自定义 TCP：推送通路与心跳设计

> 2026-09-20。取代 `2026-08-26-sdncustom-design.md` §5 的报文格式定义（见文末「文档更新」）。
> 当前实现链路见 `docs/tcp-pipeline.md`——那份描述 as-is，本 spec 描述 to-be。

## 1. 背景

自定义 TCP 协议的帧格式里定义了 `0x05 测点值变化推送`，但适配器从未实现它：`receive()` 取到一帧后
断言"必须是我要的响应"，不是就抛异常并关连接（`CustomTcpAdapter.java:163`）。后果是**任何会主动
推送的设备都会把链路打断**——推一帧 → 被当成在途请求的响应 → 断言失败 → 关连接 → 重连 → 再推 →
再断，通道在高频抖动中基本取不到值。同一个断言也让对端自发的心跳、未知命令码变成致命错误。

`0x10 心跳请求` 同样只有 mock 侧有应答分支（`MockTcpServer.java:252`），适配器从不主动发，对要求
客户端保活（看门狗）的设备表现为"连上就被踢"，重连也救不回来。

根因是一条架构错配：**协议是异步双向的（设备可主动发帧），而适配器的读模型是一发一收的同步
请求-响应**。这个模型在"纯轮询采集"下够用，一旦要支持设备主动帧就处处漏——推送要挤进响应位置、
心跳要排队等锁、心跳响应会污染采集的读循环。

## 2. 目标与非目标

### 目标

- 设备主动推送的 `0x05` 帧成为**独立数据通路**，与采集批次解耦，不被采集周期丢弃、不拉长采集预算
- 心跳：适配器能按配置周期主动发 `0x10`，兼容要求保活的设备；失败即关连接交重连调度器
- 顺带消解读模型的错配：卡读不再阻塞推送与心跳；超时不再必然杀连接

### 非目标（明确不做）

| 不做 | 理由 |
|---|---|
| 支持"只推不读"（停轮询） | 现场无此设备；轮询保留作兜底 |
| 方案 A（推送值并入 `readPoints` 返回值） | 与"推送是独立通路"的语义相反 |
| 推送值的硬实时投递保证 | 队列满丢最旧，与落库/推送队列同策略 |
| 帧格式向后兼容 | 已确认现场无按旧格式实现的设备，硬切 |
| 多通道连同一设备的连接复用 | 每通道一条 socket 是既有设计，本次不改 |
| ~~`WRITE_ONLY` 通道校验、创建测点时的通道方向校验~~ | 与本设计无关；**已作废**——通道读写开关本身被移除（2026-09-20），这两条待办不再成立 |
| 时间戳门（丢弃"比权威值旧"的推送） | 设备时钟不可信，会误丢数据；接受极小概率乱序 |

## 3. 决策记录

| 决策 | 选择 | 否决的选项与理由 |
|---|---|---|
| 推送实时性 | 独立接收线程，推送即时 | 收帧循环顺带处理（发现仍依赖轮询，通道不被轮询就收不到推送） |
| 推送值出口 | `ProtocolAdapter` 加 default `setValueListener` | 适配器直接依赖 `ChangeGate`（protocol 不可依赖 server）；上层 `instanceof` 特判（违反既有约定） |
| 推送归属 | 按 pointId 反查归属通道（规则乙） | 按连接隔离（规则甲）：多通道连同一设备时，设备集中推送的值会被静默丢弃，失效方式是"时好时坏"，排查成本高 |
| 请求关联 | 帧格式加 2 字节 RequestId | 维持"无 ID + 单在途 + 超时即断连"（超时必然杀连接，一次慢响应就造成全通道缺口） |
| 并发在途 | 支持多 pending | 人为维持单在途（读写互斥会让卡读堵住写出） |
| 超时后连接 | 保留连接；连续 3 次超时才关 | 单次超时即关（现状）；永不关（卡死通道永远不触发重连，`isConnected()` 说谎） |
| 心跳驱动 | 独立扫描器 + `spring.task.scheduling.pool.size >= 2` | 普通 `@Scheduled`（与 `acquire()`、重连调度器抢同一条线程，互相阻塞） |
| 心跳响应 | 不等待，按"ID 无匹配"丢弃 | 等待并校验（看门狗要的是流量，不是响应内容） |
| 未知命令码 | 丢弃 + 计数 | 断连（现状；对端多发一帧就杀掉链路） |

## 4. 帧格式 v2

```
[Length:4B 大端][RequestId:2B 大端][Command:1B][JSON Body:变长 UTF-8]
Length = 3 + body 字节数        （不含 4 字节头本身）
```

- **`RequestId = 0` 保留给设备主动发起的帧**（推送）。客户端请求 ID 从 1 递增到 65535 后回到 1，
  跳过 0。2 字节与 `ModbusTcpClient.transactionId` 同宽，风格一致
- 回绕安全：分配时跳过当前仍在途的 ID（在途集合最多几个，冲突可忽略）
- 接收侧**按命令码分发**，不因 ID 非 0 而丢弃推送帧（宽容取收）
- 长度守卫相应变化：`Length < 3` 或 `Length > 65535` → 关连接（原为 `< 1`）

命令码不变（`TcpCommand`）：`0x01/0x02` 读请求/响应、`0x03/0x04` 写请求/响应、
`0x05` 推送、`0x10/0x11` 心跳请求/响应。

`TcpMessage` 由 `(command, body)` 变为 `(requestId, command, body)`，`encode()`/`decode()` 同步更新。
**解析与构造收敛到这一处**——目前 `TcpMessage.decode()` 与适配器内联解析重复了一份，一并收掉。

## 5. 适配器读模型

### 5.1 结构

`CustomTcpAdapter` 内部改为「一条接收线程 + 在途请求表」：

- **接收线程**：每条通道一条**虚拟线程**（`Executors.newVirtualThreadPerTaskExecutor`），
  在 `connect()` 成功后启动，`disconnect()`/关连接后退出。只有它读 socket
- **在途请求表**：`ConcurrentHashMap<Integer, CompletableFuture<TcpMessage>>`
- **写帧锁**：一把短锁只保护"分配 ID + 登记 + 写帧 + flush"。`BufferedOutputStream.write` 不是
  线程安全的，必须串行
- **`synchronized` 不包住 socket I/O**：JDK 23 上 `synchronized` 会 pin 虚拟线程（JEP 491 到 JDK 24
  才修），阻塞读若落在同步块里会退化成平台线程

### 5.2 接收循环

```
while (connected) {
    frame = readFrame()                  // 无锁阻塞读；帧长非法即抛 → 关连接
    switch (frame.command) {
        READ_RESPONSE, WRITE_RESPONSE  → 按 RequestId 查表：命中则完成，未命中 → 丢弃 + tcp.frames.unmatched
        VALUE_PUSH                     → 解析 values 后交 listener（listener 必须快速返回）
        HEARTBEAT_RESPONSE             → 丢弃（心跳不等响应，天然无匹配）
        default                        → 丢弃 + tcp.frames.unexpected{command}
    }
}
```

退出（EOF / IOException / 主动断开）→ 异常完成**所有**在途 future，唤醒全部等待者，标记 disconnected。

### 5.3 请求侧

```
exchange(request, timeoutMs):
    future = new CompletableFuture()
    sendLock.lock()                      // 短锁：分配 ID + 登记 + 写帧
    try { id = nextRequestId(); pending.put(id, future); writeFrame(id, request); }
    finally { sendLock.unlock() }
    try {
        frame = future.get(timeoutMs)     // 阻塞调用线程，不占 socket 读权
        if (frame.command != 期望命令) → 丢弃 + tcp.frames.unexpected，抛协议错误
        return frame
    }
    catch (TimeoutException) →
        pending.remove(id); timeoutStrikes.incrementAndGet(); tcp.timeouts.increment()
        if (timeoutStrikes.get() >= sdncustom.tcp.timeout-strikes) → closeConnection()
        throw
```

超时**不再无条件关连接**（有 ID 后迟到响应不再污染后续请求），仅计数；连续超时达阈值才关连接，
把自愈交回 `ChannelReconnectScheduler`。

两个细节必须实现成显式状态，不能靠推断：

- **期望命令仍要校验**：ID 匹配只保证"响应属于这个请求"，不保证设备回的命令码正确。等待方拿到
  帧后校验 `command`，不符视为协议错误（该请求失败）
- **"连续超时"是适配器级的 `AtomicInteger`**：任一次等待超时 `+1`，**任一成功响应到达即归零**
  （由等待方在拿到帧后归零，不由接收线程猜）。多请求并发在途时以这个计数器为准，不按请求类型区分

`readPoints`/`writePoint`/`readPoint` 的外部签名与语义不变，仅内部实现改为上述机制；上层与另外
三个适配器无感。

## 6. 推送值出口

`ProtocolAdapter` 新增两个 default 方法（沿用 `onConnected`/`onPointsRemoved` 的既有约定，避免上层
`instanceof` 特判）：

```java
default void setValueListener(ValueListener listener) { }   // 订阅/推送型协议覆写
default void heartbeat() { }                                // 需要保活的协议覆写
```

`ValueListener` 定义在 protocol 模块：`void onValues(String channelId, List<PointValue> values)`。

**`onValues` 由接收线程调用，实现必须非阻塞（入队即返回）**——否则会拖慢收帧、进而影响响应交付。
这条写进接口 javadoc。

`ChannelService.connect` 在调用 `onConnected(outputPoints)` 的同一处注入 server 侧实现
（`ChannelService.java:233-235`）。

### 6.1 注入方式（**必须绕开构造期循环依赖**）

直觉写法会让 Spring 启动失败：

```
ChannelService → PushValueIngest → ValueCommitService → InputPointPropagator → ChannelService
```

`InputPointPropagator` 需要 `ChannelService` 做通道查询（`InputPointPropagator.java:86`），而
`ChannelService` 需要 listener 去注入适配器——构成环。仓库统一用 `@RequiredArgsConstructor`
构造注入，环会直接炸在启动期。

解法：`ChannelService` 用 `ObjectProvider<ValueListener>`（或 `@Lazy`）注入，
`provider.getIfAvailable()` 只在真正连接通道时取一次，DI 阶段不实例化。
`ValueListener` 由 `PushValueIngest` 实现，是 Spring 里该接口的唯一 bean。

## 7. server 侧摄取：`PushValueIngest`

`@Service`，实现 `ValueListener`：

1. `onValues(channelId, values)` → 投递到**专用单线程有界队列**（与 `PersistenceService`
   `point-store`、`DistributionService` `ws-push` 同模式：`LinkedBlockingQueue` +
   `DiscardOldestPolicy` + 丢弃计数）。队列容量 `sdncustom.push.queue-capacity`（默认 200）
2. 队列线程处理：按 pointId 批量反查测点（一次 `JpaRepository.findAllById`，避免 N+1）
   - 测点不存在 / `direction != OUTPUT` → 丢弃 + `push.rejected{reason}`
   - `sourceChannelId` 由**适配器在解析时就填成自己所属的通道**（事实来源），
     `PushValueIngest` **不得改写**它。这样 `onlyLiveSources` 的语义保持一致——来源通道是"这个值
     从哪条连接进来"，与"这个点归属哪条通道"（可能因多通道同设备而不同）是两件事
3. 按归属通道分组 → 每组调 `changeGate.filter(values, pointsById)`
4. 通过的变化合并后交给 `ValueCommitService.commit(...)`

## 8. 提交路径共用单元：`ValueCommitService`

把 `AcquisitionEngine.java:111-139` 的四步搬进新的 `ValueCommitService`，采集与推送共同调用：

```
commit(changedValues):
    publishable = onlyLiveSources(changedValues)
    if publishable.isEmpty() → return
    changed.values.increment(publishable.size())
    inputValues = propagateSafely(publishable)
    persistenceService.submitBatch(publishable + inputValues)
    pushable = onlyLiveSources(publishable) + inputValues   // INPUT 不过这道滤网（决策 A）
    if !pushable.isEmpty() → distributionService.pushBatch(pushable)
```

（`pushable` 那段的第二次 `onlyLiveSources` 保留现有语义：到这一步唯一剩下的同步 I/O 是传播的
设备写出，期间用户可能断开，迟到 GOOD 会把前端刷回正常。）

### 并发与顺序

两条线程（采集、推送队列）会并发调用 `commit`，从而并发调 `ChangeGate`。`ChangeGate` 现在是
`authority.get` 后 `authority.put`（`ChangeGate.java:57-65`），**不原子**——并发下同一 pointId 会重复
发出。改为 `ConcurrentHashMap.compute` 做原子 CAS，保证同一 pointId 不重复发、不丢更新。

保序：`PersistenceService` 单线程 FIFO 仍保证批次内与批次间保序；`ChangeGate` 原子化后同一
pointId 的变化只会有一个通过。**残留取舍**：极小概率"旧值后于新值通过"（两条线程竞速时 CAS 的
先后不等于值的时间先后）。不引入时间戳门来挡——设备时钟不可信，会误丢数据。

## 9. 归属规则（规则乙）

适配器**不做归属过滤**，原样交出"在这个连接上收到的 0x05 帧"。归属判定在 `PushValueIngest`
（server 侧，有 repository），按 pointId 反查。

理由：多通道可以连同一台设备（`ProtocolRegistry` 按 channelId 缓存实例，host/port 无唯一性校验），
设备完全可能把所有变化推在它认为的那条会话上。按连接隔离会静默丢弃这些值。

## 10. 心跳

- `CustomTcpAdapter.heartbeat()`：`sendLock` 下发 `HEARTBEAT_REQUEST`（分配正常 RequestId），
  **不等响应**，写失败即 `closeConnection()` → 交重连调度器。响应 `0x11` 由接收线程按"ID 无匹配"丢弃
- **驱动**：新增 `TcpHeartbeatScheduler`（server），`@Scheduled(fixedDelay =
  sdncustom.tcp.heartbeat.interval-ms)`；遍历 CONNECTED 通道调 `protocolRegistry.get(channelId)`
  的 `heartbeat()`（非 TCP 协议 default no-op）
- **`spring.task.scheduling.pool.size` 必须设为 ≥ 2**：`@Scheduled` 默认单线程，`acquire()`（200ms，
  内部可阻塞至 5s）与 `ChannelReconnectScheduler`（5s 扫描）目前互阻，心跳再挤上去会让三者互相拖
- 配置 `sdncustom.tcp.heartbeat.interval-ms`，**默认 0 = 关闭**：给不认识 `0x10` 的设备一个安全默认值
- 发送只需短锁，不再被在途请求的 5s 等待阻塞（相对现状的真实改善）
- **局限（如实记录）**：只发不等响应，因此**不能检测半开连接**（TCP 发送缓冲会接受写入直到收到
  RST）。看门狗要的是流量，这个局限被接受；若要半开检测，需改为等待 `0x11` + 响应超时判定

## 11. 错误处理矩阵

| 事件 | 处理 | 指标 |
|---|---|---|
| 帧长非法（<3 或 >65535） | 关连接 | `tcp.frames.invalid` |
| EOF / IOException（读） | 关连接 + 异常完成所有在途 future | `tcp.disconnects` |
| 响应 ID 无匹配 | 丢弃 | `tcp.frames.unmatched` |
| 未知命令码 | 丢弃 | `tcp.frames.unexpected{command}` |
| 单次超时 | 移除 pending，连接保留 | `tcp.timeouts` |
| 连续超时达阈值 | 关连接 | `tcp.timeouts` + 断连 |
| 写帧失败 | 关连接（沿用现状） | `tcp.disconnects` |
| 心跳发送失败 | 关连接 | `tcp.heartbeat.failures` |
| listener 抛异常 | 接收线程捕获 + 计数，**不中断接收循环** | `push.listener.errors` |
| 推送队列满 | 丢最旧批次 | `push.queue.dropped` |
| 推送点不存在/非 OUTPUT | 丢弃 | `push.rejected{reason}` |

`isConnected()` 语义不变：`connected && socket != null && !socket.isClosed()`。通道状态仍由
`ChannelService.syncDisconnected` 在采集发现断连时对齐——档 2 不改变这条链路。

## 12. 可观测性

新增（前缀沿用 `sdncustom.`）：`tcp.frames.invalid`、`tcp.frames.unmatched`、
`tcp.frames.unexpected{command}`、`tcp.timeouts`、`tcp.disconnects`、`tcp.heartbeats`、
`tcp.heartbeat.failures`、`push.values`、`push.rejected{reason}`、`push.queue.dropped`、
`push.listener.errors`。复用既有 `acquisition.failures{channel}`、`propagation.*`、
`persistence.*`、`ws.*`。

## 13. 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `sdncustom.tcp.request-timeout-ms` | 5000 | 在途请求等待上限（原 `READ_TIMEOUT_MS` 硬编码） |
| `sdncustom.tcp.timeout-strikes` | 3 | 连续超时达此值关连接 |
| `sdncustom.tcp.heartbeat.interval-ms` | 0 | 0 = 不发心跳 |
| `sdncustom.push.queue-capacity` | 200 | 推送摄取队列容量 |
| `spring.task.scheduling.pool.size` | 2 | **新增**，解开 `@Scheduled` 互阻 |
| `sdncustom.acquisition.interval-ms` | 200 | 不变 |
| `sdncustom.channel.reconnect.*` | 2s/×2/60s/5s | 不变 |

## 14. 测试计划

**protocol 单测**（扩展 `CustomTcpAdapterTest`，本地 `ServerSocket` 假设备）：

1. 推送穿插读响应：读请求后先回 `0x05` 再回 `0x02` → `readPoints` 正常返回读值，listener 收到推送
2. 推送在无在途请求时到达 → listener 收到，不影响后续请求
3. 心跳响应到达 → 被丢弃，不计入错误
4. 未知命令码 → 丢弃 + 计数，连接保持
5. 响应 ID 不匹配 → 丢弃 + 计数，**待完成的请求仍能拿到自己的响应**
6. 超时 → 抛超时、连接保留；迟到响应到达 → 丢弃
7. 连续超时达阈值 → 连接关闭
8. EOF / 帧长非法 → 所有在途 future 被异常唤醒
9. 帧编解码：`TcpMessage` 三字段往返、长度守卫边界（2/3/65535/65536）

**server 单测**（新）：`PushValueIngest` 队列丢弃、归属过滤（点不存在、非 OUTPUT、跨通道）、
`ChangeGate` 并发 CAS（多线程同一 pointId 只发一次）、`ValueCommitService` 的提交序列。

**mock 集成**：`MockTcpServer` 加推送能力（定期对已连接客户端发 `0x05`，ID=0）与 v2 帧格式；
`MockTcpServerTest` 覆盖推送与心跳应答。

**手工验证**：`mock/mock-data.json` 导入 → 连接通道 → 打开监控页，观察推送值是否到达且
`acquisition.cycle` 的 P99 不变（推送不占采集预算）。

## 15. 迁移与文档更新

- **协议硬切**：旧格式设备无法连接，无兼容开关（已确认可接受）。`sdncustom-server/data/` 不需要动
  （帧格式不入库）
- 实施完成后更新：
  - `CLAUDE.md`——“自定义 TCP 协议报文格式”一节改为 v2（含 RequestId 语义）
  - `docs/tcp-pipeline.md`——帧格式、`receive()` 一节改为接收线程模型，并删除「计划插入点」一节
  - `docs/superpowers/specs/2026-08-26-sdncustom-design.md` §5——标注被本 spec 取代
  - ~~`docs/backlog.md`——新增「`WRITE_ONLY` 通道校验」与「创建测点时校验通道方向」两条~~
    （**已作废**：通道读写开关被移除，字段不存在了）

## 16. 已知取舍

1. **超时后连接保留**：单次超时不再杀连接（好事），但卡死通道要靠"连续 3 次超时"才能自愈，
   比现状晚约 10s 才触发重连
2. **乱序窗口**：推送与采集两条线程并发经过 `ChangeGate`，原子化后仍留极小概率的"旧值后通过"
3. **推送队列满丢最旧**：与落库/推送队列同策略——那批推送值永久缺失，`push.queue.dropped` 非 0
   即意味着发生过
4. **心跳不能检测半开**：只发不等响应（见 §10）
5. **每通道一条虚拟线程**：通道数上千时线程数线性增长；虚拟线程成本低，但若实测有压力可改为
   共享的少量载体 + 每通道注册
6. **`lastHeartbeatAt` 无**：心跳周期对所有 TCP 通道一致，不支持按通道定制

## 17. 实施顺序建议

整体是一个交付物，但能切成六步，每步可独立编译、独立回归：

1. **帧格式 v2**——`TcpMessage` 加 RequestId、长度守卫改 `< 3`、`MockTcpServer` 双端、编解码测试。
   此步适配器仍是旧读模型，单在途 + 无 ID 校验也能跑通（设备回的 ID 被忽略）
2. **两个独立小改**——`spring.task.scheduling.pool.size: 2`、`ChangeGate` 原子化。先把地基铺平
3. **适配器读模型**——接收虚拟线程 + 在途请求表 + 超时策略 + 期望命令校验。
   **对外行为不变，属纯重构，全量回归现有 protocol/server 测试**
4. **推送落地**——`setValueListener` + `PushValueIngest` + `ValueCommitService` 抽取 + `ChannelService` 注入
5. **心跳**——`heartbeat()` + `TcpHeartbeatScheduler` + 配置项
6. **文档更新**——`CLAUDE.md`、`docs/tcp-pipeline.md`、design.md §5 标注取代、`backlog.md` 补两条方向缺口

第 3 步是风险最集中的一步（并发与超时语义重写），建议它单独一个提交、单独一轮回归。
