# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# SDNCustom - IoT Data Hub

通用物联网数据集中分发平台，以**测点**为核心，平台作为数据中枢统一管理所有测点数据。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 23 / Spring Boot 3.2.5 / Spring Data JPA |
| 前端 | React 18 / TypeScript / Ant Design 5 / Zustand |
| 配置存储 | H2 (嵌入式) |
| 实时缓存 | Redis |
| 历史存储 | TDengine |
| 通信 | REST API / WebSocket |

## 项目结构

```
SDNCustom/
├── sdncustom-common/      # 公共模块 - 实体、DTO、枚举、异常
├── sdncustom-protocol/    # 协议适配层 - ProtocolAdapter 接口及实现
├── sdncustom-server/      # 服务端 - REST API、WebSocket、业务逻辑、数据采集
└── sdncustom-web/         # 前端 - React SPA
```

> 原始设计文档：`docs/superpowers/specs/2026-08-26-sdncustom-design.md`（数据模型/协议层/存储设计的完整背景）。注意其中"多 Channel 写冲突"、第一期范围等章节已被后续绑定集模型与业务隔离迭代**取代**，以本文件为准。
>
> 未处理的问题与需要决策的事项集中在 **`docs/backlog.md`**（含「核实后确认不是问题」一节，避免重复讨论）。

## 核心概念

- **测点 (MeasurementPoint)**：数据的最小单元，平台的一等公民。每个测点直接关联一个通道（`channelId` + `address`，一对一）。分方向：`OUTPUT` 输出测点（关联通道用于**采集读取**，数据从外部流入平台）；`INPUT` 输入测点（`referencePointId` 引用一个**同业务、同 dataType** 的 OUTPUT 测点，其关联通道用于**写出**——输出测点经 ChangeGate 的有效变化由 `InputPointPropagator` 事件驱动写到输入测点的关联通道）。一个 INPUT 只引用一个 OUTPUT，多个 INPUT 可引用同一 OUTPUT。方向与引用创建后不可变更（update 静默忽略 DTO 中的 direction/referencePointId），删除被引用的 OUTPUT 返回 400
- **管道 (Channel)**：与外部设备/系统的连接通道，负责数据采集和写入
- **业务系统 (BusinessSystem)**：多业务隔离的逻辑维度；通道与测点归属到某个业务，列表查询/创建校验按业务过滤。测点不跨业务共享（关联通道必须同业务，引用也只能引用本业务测点）；归属创建后不可变更（update 静默忽略 DTO 中的 businessId）；删除业务前要求名下无通道、无测点。删除通道时级联删除其关联的测点。隔离是数据组织维度——单管理员、采集/推送/缓存/历史不感知业务
- **数据中枢**：平台是测点数据的唯一权威源，所有客户端通过平台读写数据

## 环境要求

- **JDK 23**：根 pom 固定 `<java.version>23</java.version>`；构建机需 JDK 23。JDK 版本较低时用 `-Djava.version=<本机版本>` 覆盖（如 21）
  - 路径不写死在 pom 里——`JAVA_HOME` 由 shell 或 `scripts/*.bat` 头部（`set JAVA_HOME=...`、`set M2_HOME=...`）提供，换机器需改脚本
- **Maven 3.9+**
- **Node.js 18+**
- **Docker**：用于运行 Redis、TDengine、Mosquitto

## 构建与运行

### 后端 (Maven)

```bash
# JAVA_HOME 需指向 JDK 23（或加 -Djava.version=<本机版本> 覆盖）
mvn clean compile                          # 编译
mvn -pl sdncustom-protocol -am compile     # 只编译某模块及其依赖
cd sdncustom-server && mvn spring-boot:run # 启动 (端口 8080)

mvn test                                        # 全部模块
mvn test -pl sdncustom-server                   # 只跑某模块（-am 会连带依赖模块）
mvn test -Dtest=ChannelServiceTest              # 运行单个测试类
mvn test -Dtest=ChannelServiceTest#testMethod   # 运行单个测试方法
```

测试只在 Java 模块里（`sdncustom-common` / `sdncustom-protocol` / `sdncustom-server`，27 个测试类、162 个 `@Test`，集中在 service/controller/security/config/monitor/repository）。`sdncustom-web` **没有测试框架**——`package.json` 无 `test` 脚本、无 vitest/jest，前端改动只能靠 `npm run build`（含 `tsc` 类型检查）、`npm run check:types`（比对后端 DTO/枚举与手写类型是否漂移）与手工验证。

### 前端 (Vite)

```bash
cd sdncustom-web
npm install
npm run dev                          # 启动开发服务器 (端口 3000)
npm run build                        # tsc 类型检查 + vite 生产构建
```

### Docker 依赖

```bash
docker-compose up -d                 # 启动 Redis + TDengine + Mosquitto
```

> 注意：Mosquitto 使用 1883（MQTT）和 9001（MQTT over WebSocket）。如果本机已占用 1883
> （例如 Windows 服务方式安装的 RabbitMQ），需先停止该服务再 `docker-compose up`。

### 脚本

```bash
scripts/build.bat                    # 构建后端（mvn clean install -DskipTests）
scripts/start.bat                    # 启动服务（后端+前端独立窗口，关闭窗口即停止）
scripts/start-backend.bat            # 单独启动后端（需先执行 build.bat）
scripts/start-frontend.bat           # 单独启动前端
```

> 注意：`scripts/start.bat` 依赖已构建的 jar，首次运行请先执行 `scripts/build.bat`。

## 协议适配器

所有协议适配器实现 `ProtocolAdapter` 接口；每个协议有一个 `ProtocolAdapterFactory`（`@Component`），Spring 启动时由 `ProtocolRegistry` 收集所有工厂，并按 channelId 管理适配器实例的生命周期（`getOrCreate`/`release`/`remove`）。连接成功后统一调用 `adapter.onConnected(points)`，**测点删除时调用 `adapter.onPointsRemoved(points)`**，订阅型协议（MQTT）在这两个钩子里增量订阅/退订，上层不再用 `instanceof` 特判：

| 协议 | 适配器类 | 说明 |
|------|---------|------|
| 自定义 TCP | `CustomTcpAdapter` | 自定义二进制协议 |
| Modbus TCP | `ModbusTcpAdapter` | 标准 Modbus TCP |
| MQTT | `MqttAdapter` | MQTT 订阅/发布 |
| OPC-UA | `OpcUaAdapter` | OPC-UA 客户端 |

**每个测点直接关联一个通道**（`channelId` + `address` 持久化字段，一对一）。`MeasurementPointRepository.findByChannelIdAndDirection(channelId, OUTPUT)` 返回该通道的所有 OUTPUT 测点供采集/订阅使用。适配器里 `point.getAddress()` 取地址是对的。

适配器是**普通类**（非 `@Component`），由对应 `XxxAdapterFactory`（`@Component`）按 `ProtocolType` 创建。新增协议 = 实现 `ProtocolAdapter` + 加一个工厂，无需改上层（订阅型重写 `onConnected` 即可，上层不 `instanceof` 特判）。

### Modbus 多寄存器（32/64 位）

INT32/FLOAT32 占 **2 个连续寄存器**、FLOAT64 占 **4 个**，起始地址取测点 `address`（如 `40031` 表示 40031-40032 两个字）；写侧走功能码 **0x10 写多个寄存器**，单寄存器/线圈仍走 0x06/0x05。

字序由通道 `connectionConfig.wordOrder` 决定：**`big`（默认，高字在前 ABCD）** / `little`（低字在前 CDAB）。字内字节固定大端，只交换寄存器顺序。**Modbus 对此无标准、设备间差异很大**——接现场设备时按手册确认这个值，配错会读到量级完全不对的数值（不报错）。

### 自定义 TCP 协议报文格式

```
[Length: 4B] [Command: 1B] [JSON Body: 变长]
```

命令码：0x01 读请求 / 0x02 读响应 / 0x03 写请求 / 0x04 写响应 / 0x05 推送 / 0x10 心跳请求 / 0x11 心跳响应

## 关键服务

- **AcquisitionEngine**：定时采集引擎，每 200ms 扫描 CONNECTED 状态的 Channel，**按通道在固定 8 线程池上并行采集**（`allOf` 限时 5s，慢通道不拖长整轮周期）；每个通道**只采 OUTPUT 测点**（`pointRepository.findByChannelIdAndDirection(channelId, OUTPUT)`），经 ChangeGate 过滤后**仅对有效变化**做处理（无变化则零写入），并调用 `InputPointPropagator` 把有效变化写到 INPUT 测点——传播值与输出测点值**并入同一批次**；该批次**异步移交** `PersistenceService` 队列落库、交给 `DistributionService` 队列推送，采集周期**不等**任何落库或推送完成；推送前的 `onlyLiveSources` 复核**不适用于传播值**（INPUT 的关联通道是写出目标，掉线只代表没送达，不代表值失效）。适配器报 `isConnected()==false` 时，非 MQTT 协议会把 DB 状态修正为 DISCONNECTED（消除"假连接"）——所以 TCP/Modbus 适配器在**读失败时会主动关闭连接**，让 `isConnected()` 如实反映
- **InputPointPropagator**：输入测点传播。值语义是「意图」而非「实际」——输出测点经 ChangeGate 的有效变化查其引用者（`findByReferencePointIdIn`），逐个**写出到 INPUT 测点的关联通道**（跳过未连接/只读通道），不论写出成功与否都用输出测点的值/质量/时间戳更新 INPUT（写出失败只记日志与指标 `sdncustom.propagation.failures`）。来源通道取写出成功的通道，失败则退回 INPUT 的 channelId。INPUT 不参与采集周期，传播值不会回流 ChangeGate，无自我触发回路
- **ChannelReconnectScheduler**：断线自动重连。`ChannelService` 维护"期望连接"集合（`connect` 加入、`disconnect` 移除、掉线不移除），调度器按 `sdncustom.channel.reconnect.*` 退避重试（默认 2s 起、×2、封顶 60s，每 5s 扫一遍）。**只管非 MQTT**——Paho 自带重连，再叠一层会重建适配器实例。指标 `sdncustom.channel.reconnects` / `reconnect.failures`
- **ChangeGate**：变更检测门。每个测点只有一个来源通道（一对一关联），直接按 pointId 记录权威值；数值型按 |新−旧| > 测点死区(deadband) 判断有效变化，非数值按 equals 判断。门的状态用 `removePoints`（删点/断连时清该点状态）维护
- **ChannelService**：Channel 生命周期唯一入口（CRUD + connect/disconnect/syncDisconnected，按通道加锁 `ReentrantLock` 串行化；DB status 是适配器运行时状态的投影）。`update()` 检测到协议/connectionConfig 变化时会先销毁旧适配器、必要时以新配置重连（修复"改配置仍沿用旧连接"）。`delete()` 级联删除该通道关联的所有测点
- **PointDirectionValidator**：测点方向校验——INPUT 必须引用一个存在、同业务、同 dataType 的 OUTPUT 测点；OUTPUT 不得带引用。引用严格单向（INPUT→OUTPUT），不可能成环
- **PointService**：测点 CRUD（创建/更新时校验方向与引用、删除被引用的 OUTPUT 报 400）、缓存批量更新
- **HistoryService**：TDengine 历史存储（超级表初始化 + 批量写入）
- **DistributionService**：WebSocket 实时数据推送（按通道合帧；采集线程仅向专用单线程队列提交任务，绝不因推送阻塞）
- **PersistenceService**：落库后台队列（Redis 实时缓存 + TDengine 历史）。采集调度线程只 `submitBatch` 快照，绝不等一次 Redis 往返（超时 3s）或一次 TDengine 写入——`acquire()` 是 `@Scheduled(fixedDelay)`，写在它里面的耗时是 1:1 吃掉采集频率的。**线程数必须恒为 1**：单线程 + FIFO 是批次间保序的唯一手段，保证 INPUT 传播值不晚于下一轮 OUTPUT 值落库（backlog A9 记的顺序隐患由此消解）。队列容量 `sdncustom.persistence.queue-capacity`（默认 200），**满则丢最旧批次**并计 `persistence.queue.dropped`——该批次的实时缓存与历史同时永久缺失，这是相对"同步写（只是慢）"新增的损失模式。停机先给 2s 排空窗口再强杀

- **DataWebSocketHandler**：每个客户端会话持有独立的有界发送队列 + 守护发送线程（`sdncustom.websocket.session-send-queue-capacity`），慢/卡死客户端只影响自己；队列溢出即断开该会话，不影响其他客户端与采集

## 数据流

```
外部系统 ──→ Channel(读关联的 OUTPUT 测点) ──→ AcquisitionEngine ──→ ChangeGate(死区/变更过滤)
                                                    ↓ 仅有效变化（批量）
                              InputPointPropagator（写到 INPUT 测点的关联通道；值并入同一批次）
                                                    ↓ 两步都只"提交即返回"，不做同步 I/O
                       ┌────────────────────────────┴────────────────────────────┐
        PersistenceService(单线程 FIFO 队列)                    DistributionService(单线程队列)
        Redis MSET(实时) + TDengine 多表INSERT(历史)            WebSocket 按通道合帧 ──→ 前端

前端 ──→ REST API ──→ PointService ──→ H2 配置库（CRUD）+ Redis 实时值（查值）
```

> 写出只有一条路径：OUTPUT 的变化经 `InputPointPropagator` 写到引用它的 INPUT 测点的关联通道。
> 前端没有直接写值的入口（`PUT /api/points/{id}/value` 已删除）。

## 启动时序

**没有任何启动期迁移或数据播种**——早期用于兼容旧表结构的 `BindingMigration` /
`BusinessSystemMigration` / `DemoDataInitializer` 已全部删除。表结构完全由 JPA
`ddl-auto: update` 依实体建出。

启动期唯一的 `CommandLineRunner` 是 `AppStartupRunner`：① `HistoryService.init()`
建 TDengine 库/超级表（失败仅告警，不阻断启动）→ ② 守护线程 sleep 2s 后
`ChannelService.autoConnectAll()`（并行连接 `autoConnect=true` 通道）。

运行时产物：H2 配置库是文件库 **`sdncustom-server/data/sdncustom.mv.db`**——路径相对后端的
工作目录（`scripts/start-backend.bat` 会 `cd sdncustom-server`），**不是仓库根目录的 `data/`**。
`data/` 由 `.gitignore` 的 `data/` 规则覆盖；`scripts/*.bat` 的输出日志写在仓库根目录的 `logs/`
（同样被 gitignore，只保留 `.gitkeep`）。

**只支持空库**：本项目不做旧库原地升级。结构变更后请删掉 `sdncustom-server/data/` 重启，
让它按当前实体重建（**删掉它即重置全部配置**，不会再有任何迁移或示例数据被回填）。
删错路径不会报错，只会拿旧库跑完整个验证——这是本仓库踩过的坑。

**空库引导**：

1. 测点管理页「导入数据」选 `mock/mock-data.json`（文件含 `businesses`、`channels`、`points` 三段，导入时自动创建业务和通道）
2. 或：先在通道管理页创建通道，再在测点管理页选择业务和通道后「导入 CSV」
3. 通道管理页逐个"连接"通道

## TDengine 历史存储

- 启动时自动创建数据库（库名取 `tdengine.url` 最后一段）并强制 `KEEP` 保留天数（`sdncustom.history.keep-days`，默认 30）
- 超级表 `point_history(ts, val, quality, source_channel_id) TAGS(point_id)`，**列名是 `val` 不是 `value`**（value 为 TDengine 3.x 保留字）
- 驱动按 URL 前缀自动选择：`jdbc:TAOS-RS://host:6041` 走 REST（纯 Java，免本机客户端）；`jdbc:TAOS://host:6030` 走原生（需安装 TDengine 客户端库）。Windows 本机验证建议用 TAOS-RS

## 可观测性

- Actuator 端点：`/actuator/health`（匿名可访问，仅 status；含自定义 `channels` 健康指示器——autoConnect 通道掉线即 DOWN；鉴权后可见 details）；`/actuator/metrics`、`/actuator/prometheus`（均需 JWT）
- 业务指标（前缀 `sdncustom_`）：acquisition.cycle（采集周期 Timer，P50/P95/P99）、acquisition.channels.connected、acquisition.failures（tag=channel）、acquisition.changed.values（有效变化数，量化死区节省）、propagation.writes / propagation.failures（输入测点写出成功/失败，均 tag=channel）、channel.reconnects / channel.reconnect.failures（断线自动重连）、history.write、history.errors、history.circuit.open（TDengine 熔断 0/1）、history.subtables.created（子表批量建立数，重启后首轮应等于当轮有变化的测点数、之后为 0）、persistence.queue.dropped（落库队列满被丢的最旧批次数，**非 0 即意味着那批数据永久缺失**）、persistence.errors（后台落库单步失败）、persistence.queue.depth（落库队列积压批次数）、ws.sessions、ws.evictions（慢客户端踢除）
- 前端仪表盘顶部为系统状态卡（通道连接数 / WS 会话 / 采集 P99 与失败 / 历史存储健康），数据源 `GET /api/system/status`（10s 轮询）
- Redis 不参与 health 判定（按设计降级）；TDengine 初始化任何失败（含原生驱动缺客户端库的 UnsatisfiedLinkError）仅告警，不阻断启动

## 前端路由

| 路径 | 页面 | 功能 |
|------|------|------|
| `/dashboard` | DashboardPage | 仪表盘，系统概览（状态卡平台级，测点表按当前业务） |
| `/businesses` | BusinessPage | 业务管理（业务系统 CRUD） |
| `/channels` | ChannelPage | Channel 管理（CRUD + 连接/断开） |
| `/points` | PointPage | 测点管理（CRUD + 按 Channel / 方向过滤） |
| `/monitor` | MonitorPage | 实时监控看板 |

> 顶栏有业务切换器（`useBusinessStore`，localStorage `sdncustom_business` 持久化）；通道/测点/监控/仪表盘均按当前业务过滤加载。

## API 端点

> 除 `POST /api/auth/login` 与 `/actuator/health` 外，全部需要 `Authorization: Bearer <token>`。

### Auth / Meta

```
POST   /api/auth/login         # 登录，返回 JWT
GET    /api/auth/me            # 当前身份
GET    /api/system/status      # 仪表盘状态卡聚合数据（通道/WS/P99/失败/历史健康）
```

### BusinessSystem

```
GET    /api/businesses           # 查询所有业务
POST   /api/businesses           # 创建
PUT    /api/businesses/{id}      # 更新（仅名称/描述）
DELETE /api/businesses/{id}      # 删除（名下有通道/测点时返回 400）
```

### Channel

```
GET    /api/channels           # 查询所有（可选 ?businessId= 过滤；传 ?size= 才返回 {items,total,page,size}，否则是全量数组）

GET    /api/channels/{id}
POST   /api/channels           # 创建（body 必填 businessId 且业务必须存在）
PUT    /api/channels/{id}      # 更新
DELETE /api/channels/{id}      # 删除
POST   /api/channels/{id}/connect     # 连接
POST   /api/channels/{id}/disconnect  # 断开
```

### MeasurementPoint

```
GET    /api/points                     # 查询所有 (可选 ?channelId=xxx / ?businessId=xxx / ?direction=INPUT|OUTPUT，可叠加；
                                       # 传 ?size= 才分页返回 {items,total,page,size})
POST   /api/points                     # 创建（body 必填 businessId、direction、channelId、address；
                                       # channelId 通道必须与测点同业务；INPUT 必填 referencePointId）
PUT    /api/points/{id}                # 更新（direction/referencePointId 与 businessId 一样被静默忽略）
DELETE /api/points/{id}                # 删除（被 INPUT 引用时返回 400，并点名引用者）
GET    /api/points/{id}/value          # 获取当前值
GET    /api/points/{id}/history        # 查询历史
```

### Data（数据导入导出）

> **JSON 导入**（`POST /api/data/import`）：payload 可含可选的 `channels` 段——缺失的通道自动创建，已存在的跳过。测点的 `channelId` 必须指向已存在或本次 payload 中提供的通道，否则整体失败并点名缺失通道。导入的每个测点**必须自带 `businessId`**（不再回退默认业务），且必须带 `channelId` 和 `address`。
>
> **CSV 导入**（`POST /api/data/import-csv`）：前端先选择业务和通道，再上传 CSV 文件。CSV 只含测点属性列（pointId, pointName, address, dataType, unit, direction, referencePointId, deadband），businessId 和 channelId 从 UI 选择继承。UTF-8 编码（兼容 BOM 头）。业务/通道不存在时自动创建。

```
GET    /api/data/export                # 导出 {businesses, points}；裸 payload（无 ApiResponse 信封），文件可直接回灌
POST   /api/data/import                # 导入 {businesses?, channels?, points}；channels 自动创建缺失通道；
                                       # 返回 {businessCount, channelCount, pointCount}，事务性
POST   /api/data/import-csv            # CSV 导入测点（multipart/form-data）；参数 businessId、channelId 由前端 UI 选择传入；
                                       # CSV 列：pointId,pointName,address,dataType,unit,direction,referencePointId,deadband
                                       # 自动创建业务/通道（如不存在），返回 {businessCount, channelCount, pointCount}
```

### WebSocket

连接：`ws://localhost:8080/ws/data`

```json
// 订阅
{"action": "subscribe", "channelIds": ["ch_001"]}
// 取消订阅
{"action": "unsubscribe", "channelIds": ["ch_001"]}
// 刷新
{"action": "refresh", "channelIds": ["ch_001"]}
```

## 编码规范

### Java 后端

- 使用 Lombok (`@Slf4j`, `@RequiredArgsConstructor`, `@Data`)
- 统一返回 `ApiResponse<T>` 包装
- 异常通过 `GlobalExceptionHandler` 全局处理
- Repository 继承 `JpaRepository`
- 协议适配器无静态单例缓存；实例由 `ProtocolRegistry`（Spring 管理）按 channelId 创建与释放
- **`@Transactional` 里不做远端 I/O**：订阅/退订适配器、连断开通道、Redis 写、WS 推送一律用 `TransactionHooks.afterCommit(...)` 挪到提交之后——否则整段时间占着 DB 连接做网络 I/O，且回滚时远端已经动过。无活动事务时该助手立即执行（单测/采集线程行为不变）

### TypeScript 前端

- 状态管理：Zustand store (`useChannelStore`, `usePointStore`)
- API 调用：`services/api.ts` 封装 axios
- WebSocket：`services/websocket.ts` 封装，支持事件监听
- UI 组件：Ant Design 5

## Mock 服务器

模拟器**实现**在 `sdncustom-protocol/src/main/java/com/sdncustom/protocol/mock/`（随 protocol 模块编译）；`mock/` 目录有启动脚本和数据文件（`mock-data.json`）：

```bash
cd mock
./build-and-start.bat              # 构建 protocol 模块 fat jar 并拉起所有 Mock 服务器（Windows）
./start-mock-servers.sh            # Linux/Mac 等价脚本
```

| Mock 服务器 | 端口 | 说明 |
|-------------|------|------|
| MockTcpServer | 9002 | 模拟自定义 TCP 协议设备 |
| MockModbusTcpServer | 5020 | 模拟 Modbus TCP 设备（支持 0x10 多寄存器写，预置 40031/32=FLOAT32、40033/34=INT32、40035-38=FLOAT64） |
| MockMqttClient | 1883 | 模拟 MQTT 传感器（需 MQTT Broker） |
| MockOpcUaServer | 4840 | 模拟 OPC-UA 服务器 |

> 注意：自定义 TCP 模拟服务器使用 **9002** 端口（9001 已被 Mosquitto 的 MQTT WebSocket 占用）。

`mock/mock-data.json` 是 `{businesses, channels, points}` 格式，正好是 `POST /api/data/import` 的入参——导入时自动创建业务和通道（已存在的跳过），然后创建测点。所有示例通道 `autoConnect=false`，导入后需手动连接。

## 安全配置

- 全站启用 JWT 认证（Spring Security，无状态）。唯一内置管理员账号配置在 `application.yml` 的 `sdncustom.security`：
  - 默认 `admin` / `changeme`，生产环境必须通过 `SDNCUSTOM_SECURITY_USERNAME` / `SDNCUSTOM_SECURITY_PASSWORD` 覆盖
  - `SDNCUSTOM_JWT_SECRET`：签名密钥（至少 32 字节）；为空时启动自动生成随机密钥（重启后已发 token 失效，仅限开发）
- 登录接口：`POST /api/auth/login`；WebSocket 握手需携带 `?token=`，REST 需 `Authorization: Bearer` 头
- 连接配置（通道）不再出现在数据导入导出中；`GET /api/channels` 返回明文 `connectionConfig`，故该接口仅限管理员使用
- H2 console 已禁用；TDengine 凭据可通过 `TDENGINE_USERNAME` / `TDENGINE_PASSWORD` 覆盖
- 前端登录页位于 `/login`，token 存 localStorage（key: `sdncustom_token`）

## 端口

| 服务 | 端口 |
|------|------|
| 后端 API | 8080 |
| 前端 Dev | 3000 |
| Redis | 6379 |
| TDengine | 6041 |
| Mosquitto | 1883 |

> **Windows 上 8080 可能起不来**：8080/8090 等端口可能落在 Hyper-V/Docker 预留的排除端口段内（`netsh interface ipv4 show excludedportrange protocol=tcp` 可见 `8057-8156` 这类区段），报错是 `Port 8080 was already in use`，但 `netstat` 上根本看不到占用。换端口，或 `net stop winnat && net start winnat` 让它重排。
