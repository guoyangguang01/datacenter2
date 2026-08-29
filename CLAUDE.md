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

## 核心概念

- **测点 (MeasurementPoint)**：数据的最小单元，平台的一等公民
- **管道 (Channel)**：与外部设备/系统的连接通道，负责数据采集和写入
- **数据中枢**：平台是测点数据的唯一权威源，所有客户端通过平台读写数据

## 环境要求

- **JDK 23**：项目使用 OpenJDK 23
  - 安装路径：`C:\Users\guoya\.jdks\openjdk-23.0.2`
  - 环境变量：`JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2`
  - 脚本中已配置：`scripts/build.bat` 和 `scripts/start.bat` 会自动设置 JAVA_HOME
- **Maven 3.9+**：`D:\dev\software\apache-maven-3.9.9`
- **Node.js 18+**
- **Docker**：用于运行 Redis、TDengine、Mosquitto

## 构建与运行

### 后端 (Maven)

```bash
# 确保 JAVA_HOME 指向 JDK 23
export JAVA_HOME=C:/Users/guoya/.jdks/openjdk-23.0.2  # Linux/Mac
# set JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2   # Windows

mvn clean compile                    # 编译
cd sdncustom-server && mvn spring-boot:run  # 启动 (端口 8080)
mvn test                             # 运行所有测试
mvn test -Dtest=ChannelServiceTest   # 运行单个测试类
mvn test -Dtest=ChannelServiceTest#testMethod  # 运行单个测试方法
```

### 前端 (Vite)

```bash
cd sdncustom-web
npm install
npm run dev                          # 启动开发服务器 (端口 3000)
npm run build                        # 构建生产版本
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

所有协议适配器实现 `ProtocolAdapter` 接口；每个协议有一个 `ProtocolAdapterFactory`（`@Component`），Spring 启动时由 `ProtocolRegistry` 收集所有工厂，并按 channelId 管理适配器实例的生命周期（`getOrCreate`/`release`/`remove`）。连接成功后统一调用 `adapter.onConnected(points)`，订阅型协议（MQTT）在此建立/增量订阅，上层不再用 `instanceof` 特判：

| 协议 | 适配器类 | 说明 |
|------|---------|------|
| 自定义 TCP | `CustomTcpAdapter` | 自定义二进制协议 |
| Modbus TCP | `ModbusTcpAdapter` | 标准 Modbus TCP |
| MQTT | `MqttAdapter` | MQTT 订阅/发布 |
| OPC-UA | `OpcUaAdapter` | OPC-UA 客户端 |

### 自定义 TCP 协议报文格式

```
[Length: 4B] [Command: 1B] [JSON Body: 变长]
```

命令码：0x01 读请求 / 0x02 读响应 / 0x03 写请求 / 0x04 写响应 / 0x05 推送 / 0x10 心跳请求 / 0x11 心跳响应

## 关键服务

- **AcquisitionEngine**：定时采集引擎，每 200ms 扫描 CONNECTED 状态的 Channel 并读取测点值；经 ChangeGate 过滤后**仅对有效变化**做批量落库与推送（无变化则零写入）
- **ChangeGate**：变更检测门 + 多来源合并。一个测点可绑定多个通道来源（主绑定 `MeasurementPoint.channelId+address` + 附加来源表 `point_source`），权威值 = 质量优先（GOOD>UNCERTAIN>BAD>COMM_LOST）→ 时间戳最新 → 来源键稳定平局；数值型按 |新−旧| > 测点死区(deadband) 判断对权威值增量生效；手动写值会同步门状态避免重复上报。写入广播到所有绑定通道（跳过未连接/只读），WS 推送按绑定通道 fan-out
- **ChannelService**：Channel 生命周期唯一入口（CRUD + connect/disconnect/syncDisconnected，按通道加锁串行化；DB status 是适配器运行时状态的投影）
- **PointService**：测点 CRUD、手动写入（writeValue）、缓存批量更新
- **HistoryService**：TDengine 历史存储（超级表初始化 + 批量写入）
- **DistributionService**：WebSocket 实时数据推送（按通道合帧；采集线程仅向专用单线程队列提交任务，绝不因推送阻塞）
- **DataWebSocketHandler**：每个客户端会话持有独立的有界发送队列 + 守护发送线程（`sdncustom.websocket.session-send-queue-capacity`），慢/卡死客户端只影响自己；队列溢出即断开该会话，不影响其他客户端与采集

## 数据流

```
外部系统 ──→ Channel ──→ AcquisitionEngine ──→ ChangeGate(死区/变更过滤)
                                                    ↓ 仅有效变化（批量）
                              Redis MSET(实时) + TDengine 多表INSERT(历史) + WebSocket按通道合帧推送
                                                    ↓
                          DistributionService ──→ WebSocket ──→ 前端

前端 ──→ REST API ──→ PointService ──→ Redis + Channel ──→ 外部系统
```

## TDengine 历史存储

- 启动时自动创建数据库（库名取 `tdengine.url` 最后一段）并强制 `KEEP` 保留天数（`sdncustom.history.keep-days`，默认 30）
- 超级表 `point_history(ts, val, quality, source_channel_id) TAGS(point_id)`，**列名是 `val` 不是 `value`**（value 为 TDengine 3.x 保留字）
- 驱动按 URL 前缀自动选择：`jdbc:TAOS-RS://host:6041` 走 REST（纯 Java，免本机客户端）；`jdbc:TAOS://host:6030` 走原生（需安装 TDengine 客户端库）。Windows 本机验证建议用 TAOS-RS

## 可观测性

- Actuator 端点：`/actuator/health`（匿名可访问，仅 status；含自定义 `channels` 健康指示器——autoConnect 通道掉线即 DOWN；鉴权后可见 details）；`/actuator/metrics`、`/actuator/prometheus`（均需 JWT）
- 业务指标（前缀 `sdncustom_`）：acquisition.cycle（采集周期 Timer，P50/P95/P99）、acquisition.channels.connected、acquisition.failures（tag=channel）、acquisition.changed.values（有效变化数，量化死区节省）、history.write、history.errors、history.circuit.open（TDengine 熔断 0/1）、ws.sessions、ws.evictions（慢客户端踢除）
- 前端仪表盘顶部为系统状态卡（通道连接数 / WS 会话 / 采集 P99 与失败 / 历史存储健康），数据源 `GET /api/system/status`（10s 轮询）
- Redis 不参与 health 判定（按设计降级）；TDengine 初始化任何失败（含原生驱动缺客户端库的 UnsatisfiedLinkError）仅告警，不阻断启动

## 前端路由

| 路径 | 页面 | 功能 |
|------|------|------|
| `/dashboard` | DashboardPage | 仪表盘，系统概览 |
| `/channels` | ChannelPage | Channel 管理（CRUD + 连接/断开） |
| `/points` | PointPage | 测点管理（CRUD + 按 Channel 过滤） |
| `/monitor` | MonitorPage | 实时监控看板 |

## API 端点

### Channel

```
GET    /api/channels           # 查询所有
POST   /api/channels           # 创建
PUT    /api/channels/{id}      # 更新
DELETE /api/channels/{id}      # 删除
POST   /api/channels/{id}/connect     # 连接
POST   /api/channels/{id}/disconnect  # 断开
```

### MeasurementPoint

```
GET    /api/points                     # 查询所有 (可选 ?channelId=xxx)
POST   /api/points                     # 创建
PUT    /api/points/{id}                # 更新
DELETE /api/points/{id}                # 删除
GET    /api/points/{id}/value          # 获取当前值
PUT    /api/points/{id}/value          # 写入值
GET    /api/points/{id}/history        # 查询历史
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

### TypeScript 前端

- 状态管理：Zustand store (`useChannelStore`, `usePointStore`)
- API 调用：`services/api.ts` 封装 axios
- WebSocket：`services/websocket.ts` 封装，支持事件监听
- UI 组件：Ant Design 5

## Mock 服务器

项目包含模拟服务器用于测试：

```bash
cd mock
./build-and-start.bat              # 构建并启动所有 Mock 服务器
```

| Mock 服务器 | 端口 | 说明 |
|-------------|------|------|
| MockTcpServer | 9002 | 模拟自定义 TCP 协议设备 |
| MockModbusTcpServer | 5020 | 模拟 Modbus TCP 设备 |
| MockMqttClient | 1883 | 模拟 MQTT 传感器（需 MQTT Broker） |
| MockOpcUaServer | 4840 | 模拟 OPC-UA 服务器 |

> 注意：自定义 TCP 模拟服务器使用 **9002** 端口（9001 已被 Mosquitto 的 MQTT WebSocket 占用）。

## 安全配置

- 全站启用 JWT 认证（Spring Security，无状态）。唯一内置管理员账号配置在 `application.yml` 的 `sdncustom.security`：
  - 默认 `admin` / `changeme`，生产环境必须通过 `SDNCUSTOM_SECURITY_USERNAME` / `SDNCUSTOM_SECURITY_PASSWORD` 覆盖
  - `SDNCUSTOM_JWT_SECRET`：签名密钥（至少 32 字节）；为空时启动自动生成随机密钥（重启后已发 token 失效，仅限开发）
- 登录接口：`POST /api/auth/login`；WebSocket 握手需携带 `?token=`，REST 需 `Authorization: Bearer` 头
- `/api/channels/export` 导出的 connectionConfig 中密码类字段会被脱敏为 `******`
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
