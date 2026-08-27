# SDNCustom - IoT Data Hub

通用物联网数据集中分发平台，以**测点**为核心，平台作为数据中枢统一管理所有测点数据。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17 / Spring Boot 3.2.5 / Spring Data JPA |
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

## 构建与运行

### 后端 (Maven)

```bash
mvn clean compile                    # 编译
cd sdncustom-server && mvn spring-boot:run  # 启动 (端口 8080)
```

### 前端 (Vite)

```bash
cd sdncustom-web
npm install
npm run dev                          # 启动开发服务器 (端口 3000)
```

### Docker 依赖

```bash
docker-compose up -d                 # 启动 Redis + TDengine
```

### 脚本

```bash
scripts/build.bat                    # 全量构建
scripts/start.bat                    # 启动服务
scripts/stop.bat                     # 停止服务
scripts/restart.bat                  # 重启服务
```

## 协议适配器

所有协议适配器实现 `ProtocolAdapter` 接口，通过 `ProtocolRegistry` 注册：

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

- **AcquisitionEngine**：定时采集引擎，每 200ms 扫描 CONNECTED 状态的 Channel 并读取测点值
- **ChannelService**：Channel 生命周期管理（CRUD + 连接/断开）
- **PointService**：测点值更新（含死区判断）、缓存管理
- **HistoryService**：TDengine 历史数据存储
- **DistributionService**：WebSocket 实时数据推送

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
- 协议适配器使用静态 `ConcurrentHashMap` 缓存实例（按 channelId）

### TypeScript 前端

- 状态管理：Zustand store (`useChannelStore`, `usePointStore`)
- API 调用：`services/api.ts` 封装 axios
- WebSocket：`services/websocket.ts` 封装，支持事件监听
- UI 组件：Ant Design 5

## 端口

| 服务 | 端口 |
|------|------|
| 后端 API | 8080 |
| 前端 Dev | 3000 |
| Redis | 6379 |
| TDengine | 6041 |
