# TCP（自定义协议）执行链路

> 描述**当前实现**（as-is），引用具体文件与行号。文末「计划插入点」一节是尚未实现的设计，
> 不要与现状混淆。
>
> 适用对象：`ProtocolType.CUSTOM_TCP` 通道（适配器 `CustomTcpAdapter`，报文格式
> `[Length:4B][Command:1B][JSON Body]`）。Modbus / MQTT / OPC-UA 各有独立链路，见
> `CLAUDE.md` 的协议适配器一节。

## 参与组件

| 组件 | 模块 | 职责 |
|---|---|---|
| `CustomTcpAdapter` | protocol | socket 生命周期、请求-响应收发、帧编码解码 |
| `ProtocolRegistry` | protocol | 按 channelId 管理适配器实例（`getOrCreate`/`release`/`remove`） |
| `AcquisitionEngine` | server | 200ms 周期扫描、按通道并行读取、变更过滤、把批次提交给传播阶段 |
| `ChangeGate` | server | 逐测点权威值 + 死区/等值判定，只放行有效变化 |
| `LiveSourceChecker` | server | 丢掉来源通道已不在 CONNECTED 的迟到值（采集提交前、传播写出后各一次） |
| `PropagationService` | server | 传播阶段后台队列：单线程做设备写出，再把 `[OUTPUT + INPUT]` 合并成一批提交 |
| `InputPointPropagator` | server | OUTPUT 变化写出到引用它的 INPUT 的关联通道（跑在 `propagation` 线程上） |
| `PersistenceService` | server | 后台队列：Redis 实时值 + TDengine 历史 |
| `DistributionService` | server | 后台队列：WebSocket 按通道合帧推送 |
| `ChannelService` | server | 通道生命周期唯一入口（按通道加锁串行化） |
| `ChannelReconnectScheduler` | server | 非 MQTT 通道的断线退避重连 |

## 阶段 0：连接（低频，用户点「连接」或启动自动连）

1. `ChannelService.connect(channelId)`（`ChannelService.java:212`）——按通道加锁 →
   记入期望连接集合 `desiredConnected`（`:218`，重连调度器认这个集合）→
   `protocolRegistry.getOrCreate(channel)`（`:219`，**每 channelId 一个适配器实例**）
2. `CustomTcpAdapter.connect`（`CustomTcpAdapter.java:40`）——解析 `connectionConfig` 的
   host/port → `Socket.connect(5s)` → `setSoTimeout(5s)` → 包上
   `BufferedInputStream`/`BufferedOutputStream` → `connected = true`
3. 回到 `ChannelService.connect:233-235`——查出该通道 **OUTPUT** 测点 →
   `adapter.onConnected(outputPoints)`（TCP 是 no-op；MQTT 在这里建立订阅）
4. DB `status = CONNECTED` + `pushChannelStatus` 推给前端

## 阶段 1：采集（每 200ms，热路径）

5. `AcquisitionEngine.acquire()`（`AcquisitionEngine.java:67`），
   `@Scheduled(fixedDelay = sdncustom.acquisition.interval-ms:200)`
6. `findByStatus(CONNECTED)` 取通道 → 每通道一个 future 丢进 **8 线程池**
   （`Executors.newFixedThreadPool(8)`，`:49`）→ `allOf().get(5s)` 限时（`:92-93`，
   `CYCLE_WAIT_SECONDS = 5`）。**超时的 future 本轮结果整个丢弃**——后续只消费
   `isDone` 的（`:101-104`）
7. `acquireChannel(channel)`（`:129`）——查该通道 OUTPUT 测点（**每轮查一次 DB**）；
   **空则直接 return，一个字节都不发**（`:134`）
8. `isConnected()` 为假 → 非 MQTT 走 `channelService.syncDisconnected`（`:140-147`），
   消除「假连接」
9. **`adapter.readPoints(points)`（`:149`）← TCP 链路入口**
10. `CustomTcpAdapter.readPoints`（`CustomTcpAdapter.java:147`）——
    `synchronized(lock)` **把整个请求-响应包在锁里**（`:151`）→ 组 body
    （pointIds + addresses）→ `send(READ_REQUEST)`（`:160`）→ `receive()` 取**一帧**
    （`:162`）→ 断言 `command == READ_RESPONSE`，不是就抛（`:163`）→ 解析 values，
    填 `sourceChannelId` / 时间戳 / 质量 → 返回
11. 任何异常 → `connected = false` + `closeConnection()` + 抛 `Read failed`
    （`:183-190`）
12. `changeGate.filter(values, pointsById)`（`:156`）——按 pointId 比权威值：质量变了
    或数值超死区或非数值不同才放行（`ChangeGate.java:38`、`:67`）
13. 回到 `acquire():107-119`——`LiveSourceChecker.onlyLive` 丢掉来源通道已断的迟到值
    （`:109`）→ `propagationService.submitBatch(publishable)`（`:118`）。**采集线程到此为止**：
    只提交快照、立即返回，后面的设备写出与落库/推送都在传播阶段（阶段 2）

### 帧的收发规则（`receive()`，`CustomTcpAdapter.java:222`）

- `readNBytes(4)` 读长度头，再 `readNBytes(length)` 读 command+body。
  **粘包/拆包都已处理**：`readNBytes` 读满为止，多读的字节留在 `BufferedInputStream`
  缓冲区里，下一次 `receive()` 从下一帧的帧头开始
- 长度守卫：`length <= 0` 或 `length > 65535` → 关连接抛异常（`:234-243`）
- 读不满（对端半路关闭）→ 抛 `Incomplete message`
- **没有重同步机制**：帧头无 magic，字节流错位后无法自愈，只能关连接靠重连恢复
- 帧格式定义在 `TcpMessage.encode()`（`TcpMessage.java:24`），长度为
  `1 + body 字节数`（不含 4 字节头本身）

## 阶段 2：传播（**在 `PropagationService` 的后台线程上**）

> 2026-09-20 起这一段**不再跑在采集线程上**（原因见 backlog A9：一台写超时的设备会拉长
> 所有通道共用的采集周期）。**`InputPointPropagator` 本身一行未改**——上面阶段 1 的第 13 步
> 只 `propagationService.submitBatch(publishable)` 入队，下列步骤全部发生在
> `PropagationService.process`（线程名 `propagation`，单线程 FIFO）里。

14. `InputPointPropagator.propagate(outputChanges)`（`InputPointPropagator.java:41`）——
    `findByReferencePointIdIn` 一次查出引用这些 OUTPUT 的 INPUT → 逐个
    `writeToChannel:79`：通道不存在 / 未连接就跳过（`:81-95`）→
    `adapter.writePoint(inputPoint, value)`（`:98`）
    → `CustomTcpAdapter.writePoint:119`：**同一把 `lock`**（`:123`）→ 发
    `WRITE_REQUEST` → `receive()` 再取一帧 → 断言 `WRITE_RESPONSE`（`:133`）；失败即
    关连接（`:139-141`）
15. INPUT 值取 OUTPUT 的值/质量/时间戳；写出成功取该通道为来源，失败退回 INPUT 自己的
    `channelId`（`toInputValue:115`）。写出失败只记 `propagation.failures`，**不降质量**
16. `PropagationService.propagateSafely`（`PropagationService.java:150`）吞掉传播异常——
    `changeGate.filter` 已经推进了变更基线，异常若逃出去这批变化值就永久丢失。它返回空列表时
    只落库/推送 OUTPUT 值；整批级别的兜底是 `process` 的 catch（`:137`）→ `propagation.batch.errors`

**调用方与合并**：调用方是 `PropagationService.process`（线程 `propagation`），采集线程只
`submitBatch`。写完再把 `[OUTPUT + INPUT]` 合并成**同一批** `persistenceService.submitBatch`
（`:128`）——批次间保序由此从"采集线程上同批"换成"单线程 FIFO 的同一个生产者"；推送前再过一次
`LiveSourceChecker.onlyLive`（`:132`，只滤 OUTPUT，INPUT 值不过）。

## 阶段 3-4：落库与分发（异步，均「提交即返回」）

17. `PersistenceService.submitBatch` → `point-store` **单线程** FIFO（
    `PersistenceService.java:59`）：Redis MSET + TDengine 多表 INSERT。**生产者只有
    `PropagationService` 一个**（采集线程不再直接提交），进出的批次已经是 `[OUTPUT + INPUT]`
    合并后的那一批。队列容量
    `sdncustom.persistence.queue-capacity:200`，满则丢最旧批次 + `persistence.queue.dropped`
    （非 0 即意味着那批数据永久缺失）
18. `DistributionService.pushBatch` → `ws-push` **单线程**（`DistributionService.java:38`）
    → 按通道合帧 → 每会话独立发送队列（`session-send-queue-capacity:512`，溢出即断该会话）
    → 前端 WebSocket

## 阶段 5：断线与重连

19. 采集发现 `isConnected() == false` → `syncDisconnected`（`ChannelService.java:296`）：
    `registry.remove` + `markPointsCommLost`（`:315`，**只标 OUTPUT**：清 ChangeGate 基线 +
    写 COMM_LOST + 推前端）+ DB = DISCONNECTED + 推状态
20. `ChannelReconnectScheduler`（`@Scheduled` 5s 扫一遍）按 `desiredConnected` 退避重连：
    `2000ms × 2^(连续失败-1)` 封顶 60000ms；**只管非 MQTT**

## 线程与锁的真实边界

```
Spring 调度线程（@Scheduled 默认单线程）
  └─ acquire()  每 200ms；内部 allOf 最多阻塞 5s
        └─ 8 线程池：每通道一个 future
              └─ readPoints（持 lock，最长 READ_TIMEOUT 5s）
                    └─ 退出 lock 后在本池线程上继续跑：
                       changeGate.filter → onlyLive → propagationService.submitBatch（到此返回）
propagation  单线程 FIFO ← submitBatch（快照）
  └─ propagate（真实 socket 写出，持同一把 lock）→ 合并 [OUTPUT + INPUT] → 两次入队
       ├─ point-store.submitBatch
       └─ ws-push.pushBatch
point-store 单线程 FIFO  ← 唯一生产者是 propagation
ws-push     单线程 FIFO  ← pushBatch（采集环上只有 propagation 调；断线时 ChannelService
                          还会推一批 COMM_LOST，`ChannelService.java:328`）
```

三条会影响后续设计的边界：

1. **`@Scheduled` 默认单线程**（本仓库未配 `spring.task.scheduling.pool.size`）→
   `acquire()` 与 `ChannelReconnectScheduler` 共用一条线程。`acquire()` 的 5s 等待期间，
   重连扫描是停摆的
2. **TCP 读写共用一条 socket、一把 `lock`** → 同一通道上「一次卡住的读」会把写出排在
   后面；且 `synchronized` 无超时、不可中断
3. **传播已搬出采集线程**（`propagation` 单线程 FIFO，原 backlog A9，2026-09-20 已处理）→
   采集线程自身只做读取 + 过滤 + 入队、不执行设备 I/O。**但同通道的读仍会与写出争上面第 2 条那把锁**
   （同时挂 OUTPUT 与 INPUT 的通道，写出最长把该通道的读顶出 5s 窗口；无 INPUT 的通道才是完全解耦的）。
   剩下的边界是**写出排队**：队列无界（决策是写命令不丢），
   设备长期慢写会让队列持续增长，失败模式是 OOM 而不是丢数据；安全阀是
   `sdncustom.propagation.queue.depth` 与深度超阈值的一次性 WARN

## 时间参数一览

| 参数 | 值 | 位置 |
|---|---|---|
| 采集周期 | 200ms | `sdncustom.acquisition.interval-ms` |
| 采集轮等待上限 | 5s | `AcquisitionEngine.java:46` |
| 传播停机排空窗口 | 5s | `sdncustom.propagation.drain-timeout-seconds` |
| 传播队列告警深度 | 100 批 | `sdncustom.propagation.queue-warn-depth` |
| 连接超时 / 读超时 | 5s / 5s | `CustomTcpAdapter.java:25-26` |
| 帧长上限 | 65535 | `CustomTcpAdapter.java:239` |
| 重连扫描 / 初始退避 / 封顶 | 5s / 2s / 60s | `sdncustom.channel.reconnect.*` |
| 落库 / 推送队列容量 | 200 / 200 | `sdncustom.persistence.queue-capacity` 等 |

## 计划插入点（**尚未实现**）

推送与心跳的设计位置，详见 `docs/superpowers/specs/` 下的 spec（撰写中）：

- **推送 0x05 的唯一插入点是第 10 步的 `receive()`**：由「取一帧、断言是我要的」改为
  **收帧循环**——`0x02` 返回、`0x05` 解析攒下、`0x11` 与未知命令码丢弃计数、撞上限
  （32 帧/次）关连接
- **心跳**：新增 `@Scheduled` 扫描器对 CONNECTED 通道调 `adapter.heartbeat()`
  （`ProtocolAdapter` 加 default no-op，避免上层 `instanceof` 特判），适配器内**只发
  0x10、不等响应**，发送失败即关连接交给重连调度器。心跳响应 `0x11` 会落在采集线程的
  收帧循环里，按「非预期帧」丢弃——由同一套规则覆盖
- **协议层的硬约束**：`ChangeGate` 在 server 模块，适配器在 protocol 模块，
  **protocol 不依赖 server**，所以推送值必须先回到 server 侧才能进变更门
