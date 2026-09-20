# 待办与已知问题

> 2026-09-14 一轮集中排查 + 修复后的**遗留清单**。
> 同日晚些时候的「测点方向」迭代收尾时补入 **A9 / A10 / B12**（均为该迭代新引入或新暴露的债）。
> 分三类：还能直接做的、需要先定方案的、以及**核实过并非问题**的（写出来是为了以后不要重复讨论）。
> 本轮已完成的部分见文末，提交 `53fb74f`。

行号可能随改动漂移，定位以方法/类名为准。

---

## 一、可直接做（无需产品决策）

### B6 推送队列满时静默丢数据
`DistributionService` 的推送线程池用 `ThreadPoolExecutor.DiscardOldestPolicy`，队列满时**丢最旧批次且无日志、无指标**。背压下实时数据悄悄消失，事后无法从指标看出发生过。
→ 至少加一个 `sdncustom.ws.push.dropped` 计数 + WARN 日志；是否改为阻塞/反压另议。

### B7 启动自动连接用 commonPool + 魔法 sleep
`ChannelService.autoConnectAll()` 用 `parallelStream()`，连接是 5s 级阻塞 I/O，会占用 JVM 全局 `ForkJoinPool.commonPool`，拖累其它 parallel stream。`AppStartupRunner` 里另有硬编码 `Thread.sleep(2000)`「等表建好」。
→ 换成专用小线程池（复用 `ProtocolRegistry` 那套思路），用真实就绪信号替代 sleep。

### B11 JWT 走 URL 查询串
- 前端 WS 连接把 token 拼在 URL：`sdncustom-web/src/services/websocket.ts`
- 后端 `JwtAuthFilter` 对**所有 REST 接口**都接受 `?token=`（不只是 WS 升级）

URL 会进代理/网关/浏览器历史与访问日志。
→ REST 端只认 `Authorization` 头；WS 握手改用子协议头或握手后首帧认证。

### 测点的 `channelId` 完全没有校验（create 与 update 都不查）
`PointService.create/update` 直接 `setChannelId(dto.getChannelId())`，**不查通道是否存在、也不查是否同业务**
（`PointDirectionValidator` 只校验被**引用测点**的业务，`PointService` 里根本没有 `ChannelRepository`）。
后果：接口可创建出挂在不存在通道上的孤儿测点（采集永远读不到、写出永远跳过），也能把测点挪到别的业务的通道上，
破坏"测点不跨业务共享"这条写在 CLAUDE.md 里的不变量。前端因为只列出当前业务的通道，所以日常操作碰不到——
**这是纯后端缺口，且 CLAUDE.md 此前把它写成了已落实的规则（2026-09-20 已改成据实描述）**。
→ 修法：`create`/`update` 加同一条校验（通道存在 + `channel.businessId == dto.businessId`），
在 `PointDirectionValidator` 里加（它已有 repository，便于复用与单测）。**注意这是行为变更**：
可能拒掉现存数据里的越界测点，需先确认存量。

### B12 缺少必填查询参数报 500（应为 400）
`GET /api/points/{id}/history` 的 `startTime`/`endTime` 是必填查询参数，缺任一个时 Spring 抛
`MissingServletRequestParameterException`；`GlobalExceptionHandler` 没有对应 handler，直接落到兜底的
`Exception` 分支 → body `code:500 "Internal server error"`（HTTP 仍是 200）。同类还有路径变量缺失
（`MissingPathVariableException`）与 `ServletRequestBindingException`。
→ 按现有 4 个 handler 的写法补一个，返回 400 并点名缺失的参数。**点方向迭代明确不做**：
这是全局错误契约的一部分，不属于该特性范围。

### 其它零散债
- **Redis 故障时采集路径被拖慢**：`PointValueCacheRepository` 失败吞异常但每次调用要等 `spring.data.redis.timeout: 3000`，而它在采集环上；`saveBatch` 失败只 WARN。考虑本地降级缓存或异步写。
- **`markPointsCommLost` 里逐点查绑定**：`ChannelService` 中 `bindingChannelIds(pointId)` 按点循环查询（N+1）。不在 200ms 热路径上（仅断开/掉线时触发），量级不大时可不改。
- **无审计日志**：写操作只有 `log.info`（含用户名），不落库，事后无法查证谁改了什么。
- **历史单条写入路径是死代码**：`HistoryService.save(pv)` 与 `PointHistoryRepository.save(h)` 无任何调用者——只有 `saveBatch` 在采集环上被用（`AcquisitionEngine`）。子表建立逻辑已统一到 `ensureSubtables`，但这份死代码会让人误以为存在"单条写历史"的能力，可直接删。

---

## 二、需要先定方案（不适合直接动手）

### A1 所有错误都返回 HTTP 200
`GlobalExceptionHandler` 无 `@ResponseStatus`，业务错误靠 body 里的 `code`。实测 `GET /api/points/<不存在>` 返回 **HTTP 200 + body `code:404`**。
→ 改成真实状态码是**前后端契约变更**：每个 store 的 `res.data.code !== 200` 判断、axios 拦截器、401 重定向都要跟着动。

### A2 无权限模型 / 无业务归属校验
单管理员账号（`sdncustom.security`），`anyRequest().authenticated()`，没有「用户 → 业务」映射：
- `PointController.delete` 不校验业务归属
- WS `handleSubscribe` 不校验 `channelIds` 是否可读

→ 要先定多用户模型（角色？业务授权？），否则加校验等于先建一套用户体系。

### A3 `GET /api/channels` 返回明文凭据
`ChannelController.findAll` 直接返回实体，含 `connectionConfig`（MQTT `password` 明文）。导出那条链已不再产出配置，但读接口仍明文。
→ 三种修法要选：① 单开脱敏视图接口 ② 编辑表单不再回填密码（留空=不改）③ 字段级 `@JsonIgnore` + 专用写接口。**会牵动前端表单行为**。

### A4 TDengine 值类型 + 时间戳处理
- `val BINARY(256)`，数值一律 `String.valueOf` 存字符串；查询回来永远是 String，历史做不了数值聚合
- `safeTimestamp` 把越界时间戳**替换成当前时间**而不是丢弃/拒绝，坏数据被搬到「现在」

→ 改列类型涉及**存量历史迁移方案**（旧数据转不转、怎么转）。

### A5 熔断期间直接丢数据
`PointHistoryRepository` 熔断打开时 `save`/`saveBatch` 直接 return，**不缓冲**；单次异常即开 15s。
→ 要做缓冲就得定 spool 的落盘位置/容量/重放策略。

### A6 历史查询页完全没有
后端 `GET /api/points/{id}/history` 可用，**前端零实现**（`api.ts` 里连 history 调用都没有）。
→ 新功能，需先过交互：时间范围、图表还是表格、分页、是否导出。

### A7 无数据库迁移工具，且"只支持空库"没有升级路径
`sdncustom-server/src/main/resources/application.yml` 用 `ddl-auto: update`，schema 完全由实体建出。
`BindingMigration` / `BusinessSystemMigration` / `DemoDataInitializer` 三个启动期 runner 已全部删除
（见第五节），项目现在**只支持空库**——不是"暂时没写迁移"，而是**任何已部署实例都没有升级路径**：
旧库里的 `measurement_point` 有 `writable` 列、没有 `direction`（该列可空、无回填），
`point_source` 也不会自动补齐。唯一的走法是停服、备份、删 `sdncustom-server/data/` 重建、再按
「先通道后数据」重新导入（H2 文件库相对后端工作目录，**不是**仓库根目录的 `data/`——删错路径不报错，
只会拿旧库跑完整个验证）。
→ 是否引入 Flyway/Liquibase（并恢复可升级性）是工程决策；在那之前，任何"就地升级已有部署"的需求
都等于要求先做数据迁移。

### ~~A9 传播在采集线程上做无界设备 I/O~~（2026-09-20 已处理，见下）
`AcquisitionEngine.acquire` 在**采集调度线程**上同步调用 `InputPointPropagator.propagate`，后者对每个
INPUT 测点的每条绑定做一次 `adapter.writePoint`（真实 socket 写）。5s 的 `allOf` 限时只覆盖**读取阶段**
（每通道一个 future），传播不在其中——一台写超时的设备会把整轮采集拖长，而这是所有通道共用的周期。
设计文档 §7 把"迁移到专用执行器（同 `DistributionService` 的单线程队列 + 有界缓冲）"写成缓解措施，
但触发条件（"若实测有影响"）**从未被测量过**：本轮没有任何延迟/吞吐基线。
→ 先量再改：给 `acquire()` 的传播段单独计时（Timer 或日志），有数据再决定是否搬走。
搬走会引入异步顺序问题（INPUT 的值可能晚于下一轮 OUTPUT 的值落库）。

**→ 已搬到 `PropagationService`**（单线程 FIFO，`[OUTPUT + INPUT]` 合并成同一批提交；采集线程只
`propagationService.submitBatch(publishable)`）。顺序保证换了实现：从"两者在采集线程上同批"换成
"`PersistenceService` 的唯一生产者就是那个单线程的 `PropagationService`"。
`acquisition.cycle` 的构成随之变成"读取 + 过滤 + 入队"，**不再包含设备写出**（历史 P99 不可直接比较）。
队列选的是**无界**而不是上面设想的"有界缓冲"——写命令的丢弃语义太重，见「工程债 / 测试缺口」一节。
上面这段历史读数原样保留：它记录的是当时（落库已异步、传播仍在采集线程上）的真实构成。

> 2026-09-16 更新（落库异步化之后）：这句话里的两条前提都变了，重新读数。
> - **顺序问题已有现成解法**：`PersistenceService` 证明了"单线程 + FIFO 队列"就能保住批次间顺序
>   （INPUT 传播值与其 OUTPUT 值本就在同一批里，批次按提交序落库），搬传播时照此办理即可。
> - **基线变了**：落库（Redis + TDengine）已移出采集线程，`sdncustom.acquisition.cycle` 现在的构成是
>   "读取阶段 + 传播的设备写出 + 两次入队"。也就是说**传播占比被相对放大**，这条债的分量比之前更重了，
>   而不再需要担心"和落库混在一起量不准"。

### A10 写失败不降级：值分不清"意图"与"实际"
按设计决策，传播写出失败只用 `sdncustom.propagation.failures` 记数，INPUT 的缓存与历史**照写 OUTPUT 的
值、质量不降**。后果：消费方（前端、将来的规则引擎、历史查询）看到一个 `quality=GOOD` 的值，无法区分
「外部设备确实收到了」与「写出失败了，这只是我们希望它有的值」。一个长期掉线的写出目标，其 INPUT 在
监控页上与正常的一模一样。
→ 要区分就得先定语义：失败时降 `quality`（如 `UNCERTAIN`），还是给 `PointValue` 另加 `delivered`/`intended`
字段？两者都会改变前端展示与历史数据的含义，**需要产品决策**；改之前先确认设计文档 §1.3 的
"无论写出成功与否都用 OUTPUT 的值更新"是否仍然成立。

### A8 环境/部署相关
- 硬编码默认凭据：H2 `sa`/空密码、TDengine `root/taosdata`、admin `changeme`
- **默认端口 8080 在 Windows 上可能起不来**：本机实测 8080/8090 落在 Hyper-V/Docker 的保留端口段（`netsh interface ipv4 show excludedportrange protocol=tcp` 可见 `8057-8156` 等），报错是 `Port 8080 was already in use` 但 `netstat` 上什么都没有。绕法：换端口，或 `net stop winnat && net start winnat` 让它重排保留段

---

## 三、工程债 / 测试缺口

- **前端无测试框架**：`sdncustom-web/package.json` 无 `test` 脚本、无 vitest/jest。目前靠 `npm run build`（tsc）+ `npm run check:types`（后端 DTO/枚举漂移检查）+ 手工验证
- ~~**协议 mock 测试：固定端口 + 客户端读无超时，一次被中断的运行会毒化之后所有运行**~~（2026-09-20 已修）
  事故经过：`MockTcpServerTest` 固定用 `TEST_PORT=19001`（`MockModbusTcpServerTest` 同理，15020），且 `start()` 在
  后台线程里 bind、失败只 `log.error`——端口被上一次中断运行遗留的进程占着时调用方毫不知情，客户端于是连到那个
  僵尸服务器上，又没有 `setSoTimeout`，在 `readNBytes` 里**永久阻塞**（实测：一个 10:57 遗留的 JVM 让后续 3 次运行
  全部挂死，且看起来像"代码改坏了"）。测试里还有 `if (lengthBytes.length < 4) return;` 这类静默跳过，会把
  "服务器根本没响应"伪装成通过。
  → 已修（`MockTcpServer` / `MockModbusTcpServer` / 两个测试）：① `start()` 改**同步 bind**，端口占用直接抛
  `IllegalStateException` 并点名端口（有回归测试 `portConflictFailsLoudly` 钉住）；② 新增 `getPort()`，测试改用
  **临时端口（`0`）**，从构造上不可能与任何遗留进程冲突；③ 客户端 `setSoTimeout(5s)` + 删掉全部静默跳过分支；
  ④ 两个 mock server 的 accept/handler/定时线程改**守护线程**，调用方忘了 `stop()` 也不会把 JVM 拖住。
  通用经验仍然成立：**测试挂死先 `jps -l` + `netstat -ano | grep <端口>` 看有没有僵尸 surefire JVM，别急着怀疑代码**
- **控制器层 0 测试**：CRUD、`@Valid` 失败、异常映射都没覆盖
- **MQTT / OPC-UA 适配器无单测**（Modbus 有 mock 集成测试覆盖读写与 0x10）
- **无单测**：`HistoryService`、`PointBindingRegistry`、`SystemStatusService`、`JwtAuthFilter`、`WebSocketAuthInterceptor`
- **`PropagationService` 的队列无界（有意为之）**：决策是"写命令不丢"——在写出路径上丢最旧批次语义太重
  （那是"设备没收到"而不是"数据不可见"）。失败模式因此是**内存耗尽（OOM）而不是丢数据**；安全阀是
  `propagation.queue.depth` 指标 + 深度超 `sdncustom.propagation.queue-warn-depth`（默认 100）的一次性 WARN，
  停机另有 `sdncustom.propagation.drain-timeout-seconds`（默认 5s）排空窗口——**超时强杀是唯一会丢写命令的路径**
  （日志会点名批次数，含正在写出的那一批）。若将来现场出现"设备长期慢写"，需要回到"有界队列 + 合并"的方案，
  并重新定丢弃语义
- **B9 前端未接分页**：`GET /api/points`、`GET /api/channels` 支持可选 `page`/`size`（传 `size` 才返回 `{items,total,page,size}`，否则仍是全量数组）。这个「响应形态随参数变」的取舍是为了不动前端；要真正服务端分页得把 4 个表格改掉，**需要在浏览器里验证**
- **前端类型仍手抄后端 DTO**：有漂移检查兜底，但没有代码生成
- **`GET /api/channels` 的 `connectionConfig`**：见 A3

---

## 四、核实后确认**不是**问题（勿再改）

这几条曾被列为缺陷，逐行核实后判定不成立，记下来避免以后重复讨论：

| 曾怀疑 | 核实结论 |
|---|---|
| `PointBindingRegistry.channelsOf` 返回内部集合，会被并发改 | 集合构造后**从不被修改**；`invalidate` 删的是 map 条目，不是集合内容。无 CME 风险 |
| Redis 键无 TTL，无界增长 | 键空间以测点数为界，删除路径会清键。加 TTL 反而会丢「最后已知值」，与设计冲突 |
| WS 会话泄漏、`sessions` 无限增长 | `enqueue` 遇到 `offer` 失败会 evict + `removeSession`，是自愈的 |
| `wsService` 被任一页面卸载就断开共享 socket | 单标签页同一时刻只挂载一个页面，实际只是切页时重连一次 |
| 前端 `LoginPage` 的 `navigate('\dashboard')` 转义笔误 | 看错了——文件里本来就是 `'/dashboard'`，是 grep 输出把斜杠转义显示 |

---

## 五、本轮已处理（供追溯）

- **数据与连接配置拆分**：`/api/data/export` 只产出 `{businesses, points}`；`/api/data/import` 接受可选的 `channels` 段（自动创建缺失通道，但**不含连接配置**——通道配置必须手工填）。顺带修掉「导出→再导入把脱敏占位符 `******` 写回库、毁掉凭据」
  （注：这一条原写的是"通道配置独立走 `POST /api/channels/import`"，但该端点**从未存在过**——通道页也没有导入按钮，`mock/mock-channels.json` 因此成了无人消费的遗留文件）
- **Modbus 32/64 位读写**：原先读也只读 1 个寄存器（32 位点读回垃圾值）。新增 `ModbusRegisters`（字序 `big`/`little`，通道 `connectionConfig.wordOrder`），写走功能码 0x10
- **断线自动重连**（非 MQTT）：`ChannelReconnectScheduler` 退避 2s×2 封顶 60s；并修掉「假连接」——TCP/Modbus 读失败即关连接，`isConnected()` 才如实反映
- **采集正确性**：超时不再消费未完成的 future；推送前复核来源通道仍连接
- **多来源点基线**：断开某通道时只收敛来源集合，不再误清权威基线导致重推
- **资源泄漏**：OPC-UA/MQTT 连接失败释放半成品 client；TCP/Modbus 补连接超时
- **MQTT 退订**：`ProtocolAdapter.onPointsRemoved` 钩子，删点/改绑定会退订并清缓存
- **`@Transactional` 内不做远端 I/O**：新增 `TransactionHooks.afterCommit`
- **其它**：错误请求体 400、删除不存在测点 404、加索引、历史查询参数校验+偏移溢出保护、tdengine 库名白名单+引导超时、通道状态推送接通
  （注：原先列在这里的「`BindingMigration` 按具体绑定判重」「demo 播种开关」「写值返回逐通道结果」三项，对应代码已在「点方向」迭代中随迁移类 / `DemoDataInitializer` / 手动写值端点一起删除）
- **前端**：统一错误提示（保留后端 message）、JWT 过期判定、切业务清空 store、WS 重连指数退避、删除二次确认、修监控页地址列恒空、`check:types` 漂移检查脚本
- **禁止输入测点引用同通道的输出测点**：新增自引用禁令（`PointDirectionValidator.validate` 创建路径 +
  新增 `validateChannelChange` 走更新路径——`update` 原本**完全不调校验器**，channelId 可改，所以改 INPUT 的通道、
  改 OUTPUT 的通道两个方向都要查；导入侧在 `DataTransferService.validateReferencesExist` 同样加了一条）。
  顺带修正了两个把"同通道引用"当作合法用法的既有测试 fixture（`resolvesReferenceWithinSamePayload`、
  `ordersOutputsBeforeInputs`），并让前端引用候选排除同通道的输出测点。
  **只禁自环，不禁"一个通道同时挂两种方向的测点"**——后者仍然允许，同通道读写争用由适配器的同一把锁串行化
- **通道读写开关移除**：删除 `ChannelDirection` 枚举与 `Channel.direction`（连同 DTO / 导入解析 / 前端下拉与列 / mock 数据 / 漂移检查条目）。
  读写能力改为完全由测点方向隐含（OUTPUT=采集读、INPUT=写出）。**唯一的行为变化**：`InputPointPropagator`
  不再跳过 `READ_ONLY` 通道，只要求通道存在且 `CONNECTED`——某设备若确实不能写，正确做法是不给它建
  INPUT 测点，而不是把通道标成只读。顺带作废了 2026-09-20 TCP 心跳设计稿里「`WRITE_ONLY` 通道校验」与
  「创建测点时校验通道方向」两条待办（字段已不存在）
