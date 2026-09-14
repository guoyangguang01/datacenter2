# 待办与已知问题

> 2026-09-14 一轮集中排查 + 修复后的**遗留清单**。
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

### 其它零散债
- **Redis 故障时采集路径被拖慢**：`PointValueCacheRepository` 失败吞异常但每次调用要等 `spring.data.redis.timeout: 3000`，而它在采集环上；`saveBatch` 失败只 WARN。考虑本地降级缓存或异步写。
- **`markPointsCommLost` 里逐点查绑定**：`ChannelService` 中 `bindingChannelIds(pointId)` 按点循环查询（N+1）。不在 200ms 热路径上（仅断开/掉线时触发），量级不大时可不改。
- **部分写成功的语义**：`PointService.writeValue` 现在会返回逐通道结果，但**只要有一个通道成功**，仍把值按 GOOD 写缓存并推送。是否改为「部分失败不更新权威值」需要产品判断。
- **写值 UI 缺失**：`api.ts` 有 `writeValue`，但**没有任何页面调用**——手动写值目前只有 API。
- **无审计日志**：写操作只有 `log.info`（含用户名），不落库，事后无法查证谁改了什么。

---

## 二、需要先定方案（不适合直接动手）

### A1 所有错误都返回 HTTP 200
`GlobalExceptionHandler` 无 `@ResponseStatus`，业务错误靠 body 里的 `code`。实测 `GET /api/points/<不存在>` 返回 **HTTP 200 + body `code:404`**。
→ 改成真实状态码是**前后端契约变更**：每个 store 的 `res.data.code !== 200` 判断、axios 拦截器、401 重定向都要跟着动。

### A2 无权限模型 / 无业务归属校验
单管理员账号（`sdncustom.security`），`anyRequest().authenticated()`，没有「用户 → 业务」映射：
- `PointController.delete` / `writeValue` 不校验业务归属
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

### A7 无数据库迁移工具
`sdncustom-server/src/main/resources/application.yml` 用 `ddl-auto: update`，schema 演进靠两个手写 migration（`BindingMigration` / `BusinessSystemMigration`）。
→ 是否引入 Flyway/Liquibase 是工程决策。

### A8 环境/部署相关
- 硬编码默认凭据：H2 `sa`/空密码、TDengine `root/taosdata`、admin `changeme`
- **默认端口 8080 在 Windows 上可能起不来**：本机实测 8080/8090 落在 Hyper-V/Docker 的保留端口段（`netsh interface ipv4 show excludedportrange protocol=tcp` 可见 `8057-8156` 等），报错是 `Port 8080 was already in use` 但 `netstat` 上什么都没有。绕法：换端口，或 `net stop winnat && net start winnat` 让它重排保留段

---

## 三、工程债 / 测试缺口

- **前端无测试框架**：`sdncustom-web/package.json` 无 `test` 脚本、无 vitest/jest。目前靠 `npm run build`（tsc）+ `npm run check:types`（后端 DTO/枚举漂移检查）+ 手工验证
- **控制器层 0 测试**：CRUD、`@Valid` 失败、异常映射都没覆盖
- **MQTT / OPC-UA 适配器无单测**（Modbus 有 mock 集成测试覆盖读写与 0x10）
- **无单测**：`HistoryService`、`PointBindingRegistry`、`SystemStatusService`、`JwtAuthFilter`、`WebSocketAuthInterceptor`
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

- **数据与连接配置拆分**：`/api/data/export|import` 只承载业务与测点；通道配置独立走 `POST /api/channels/import`。顺带修掉「导出→再导入把脱敏占位符 `******` 写回库、毁掉凭据」
- **Modbus 32/64 位读写**：原先读也只读 1 个寄存器（32 位点读回垃圾值）。新增 `ModbusRegisters`（字序 `big`/`little`，通道 `connectionConfig.wordOrder`），写走功能码 0x10
- **断线自动重连**（非 MQTT）：`ChannelReconnectScheduler` 退避 2s×2 封顶 60s；并修掉「假连接」——TCP/Modbus 读失败即关连接，`isConnected()` 才如实反映
- **采集正确性**：超时不再消费未完成的 future；推送前复核来源通道仍连接
- **多来源点基线**：断开某通道时只收敛来源集合，不再误清权威基线导致重推
- **资源泄漏**：OPC-UA/MQTT 连接失败释放半成品 client；TCP/Modbus 补连接超时
- **MQTT 退订**：`ProtocolAdapter.onPointsRemoved` 钩子，删点/改绑定会退订并清缓存
- **`@Transactional` 内不做远端 I/O**：新增 `TransactionHooks.afterCommit`
- **其它**：错误请求体 400、删除不存在测点 404、加索引、历史查询参数校验+偏移溢出保护、`BindingMigration` 按具体绑定判重、tdengine 库名白名单+引导超时、demo 播种开关、通道状态推送接通、写值返回逐通道结果
- **前端**：统一错误提示（保留后端 message）、JWT 过期判定、切业务清空 store、WS 重连指数退避、删除二次确认、修监控页地址列恒空、`check:types` 漂移检查脚本
