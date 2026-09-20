# SDNCustom - IoT Data Hub

通用物联网数据集中分发平台，以**测点**为核心，平台作为数据中枢统一管理所有测点数据。

## 核心概念

- **测点 (MeasurementPoint)**：数据的最小单元，平台的一等公民。每个测点直接关联一个通道。分方向：`OUTPUT`（关联通道用于采集读取，数据从外部流入平台）与 `INPUT`（引用一个同业务、同数据类型的 OUTPUT，其关联通道用于写出——输出测点的变化会传播到引用它的输入测点并写到外部）
- **管道 (Channel)**：与外部系统的连接通道，负责数据同步。通道本身没有读写开关——读写能力由挂在它上面的测点方向决定
- **数据中枢**：平台是测点数据的唯一权威源，所有客户端通过平台读写数据

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 23 / Spring Boot 3.2 / Spring Data JPA |
| 前端 | React 18 / TypeScript / Ant Design 5 / Zustand |
| 配置存储 | H2 (嵌入式) |
| 实时缓存 | Redis |
| 历史存储 | TDengine |
| 通信 | REST API / WebSocket |

## 项目结构

```
SDNCustom/
├── sdncustom-common/      # 公共模块 - 实体、DTO、枚举
├── sdncustom-protocol/    # 协议适配层 - ProtocolAdapter 接口
├── sdncustom-server/      # 服务端 - REST API、WebSocket、业务逻辑
└── sdncustom-web/         # 前端 - React 应用
```

## 快速开始

### 前置条件

- JDK 23（根 pom 固定 `<java.version>23</java.version>`；本机版本较低时用 `-Djava.version=<版本>` 覆盖，如 21）
- Maven 3.9+
- Node.js 18+
- Redis (可选，用于实时缓存)
- TDengine (可选，用于历史数据)

### 启动后端

```bash
# 编译
mvn clean compile

# 启动服务
cd sdncustom-server
mvn spring-boot:run
```

服务启动后访问 http://localhost:8080

### 启动前端

```bash
cd sdncustom-web

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端访问 http://localhost:3000

### 默认登录凭据

| 项目 | 值 |
|------|-----|
| 用户名 | `admin` |
| 密码 | `changeme` |

> 生产环境请通过环境变量 `SDNCUSTOM_SECURITY_USERNAME` / `SDNCUSTOM_SECURITY_PASSWORD` 覆盖默认凭据，并设置 `SDNCUSTOM_JWT_SECRET`（至少 32 字节）。

## API 文档

> 除 `POST /api/auth/login` 与 `/actuator/health` 外，所有接口都需要 `Authorization: Bearer <token>`。
> 下面只列主要端点，完整清单（含业务、导入导出、系统状态）见 `CLAUDE.md`。

### Auth

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/login | 登录，返回 JWT |
| GET | /api/auth/me | 当前身份 |

### BusinessSystem API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/businesses | 查询所有业务 |
| POST | /api/businesses | 创建业务 |
| PUT | /api/businesses/{id} | 更新业务（仅名称/描述） |
| DELETE | /api/businesses/{id} | 删除业务（名下有通道/测点时返回 400） |

### Channel API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/channels | 查询所有 Channel |
| GET | /api/channels/{id} | 查询单个 Channel |
| POST | /api/channels | 创建 Channel |
| PUT | /api/channels/{id} | 更新 Channel |
| DELETE | /api/channels/{id} | 删除 Channel |
| POST | /api/channels/{id}/connect | 连接 Channel |
| POST | /api/channels/{id}/disconnect | 断开 Channel |

### MeasurementPoint API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/points | 查询所有测点 |
| GET | /api/points?channelId=xxx | 按 Channel 过滤测点 |
| GET | /api/points?direction=INPUT\|OUTPUT | 按方向过滤测点 |
| GET | /api/points/{id} | 查询单个测点 |
| POST | /api/points | 创建测点 |
| PUT | /api/points/{id} | 更新测点 |
| DELETE | /api/points/{id} | 删除测点（被输入测点引用时返回 400） |
| GET | /api/points/{id}/value | 获取测点当前值 |
| GET | /api/points/{id}/history | 查询历史数据（必填 `startTime` / `endTime`） |

### Data（导入导出）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/data/export | 导出 `{businesses, points}`（裸 payload，可直接回灌） |
| POST | /api/data/import | 导入 `{businesses?, channels?, points}`，事务性 |
| POST | /api/data/import-csv | CSV 导入测点（multipart，业务与通道由表单选择） |

### System

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/system/status | 仪表盘状态卡聚合数据（10s 轮询） |

### WebSocket

连接地址：`ws://localhost:8080/ws/data`

**订阅消息：**
```json
{
    "action": "subscribe",
    "channelIds": ["ch_001", "ch_002"]
}
```

**数据推送：**
```json
{
    "type": "data",
    "values": [
        {
            "pointId": "point_001",
            "value": 25.6,
            "quality": "GOOD",
            "timestamp": 1693000000000
        }
    ]
}
```

## 自定义 TCP 协议

报文格式：`[Length(4B)] [Command(1B)] [JSON Body]`

| 命令码 | 方向 | 说明 |
|--------|------|------|
| 0x01 | C→S | 读取测点值请求 |
| 0x02 | S→C | 读取测点值响应 |
| 0x03 | C→S | 写入测点值请求 |
| 0x04 | S→C | 写入测点值响应 |
| 0x05 | S→C | 测点值变化推送 |
| 0x10 | C→S | 心跳请求 |
| 0x11 | S→C | 心跳响应 |

## 第一期范围

- [x] Channel 管理 (CRUD + 连接/断开)
- [x] 测点管理 (CRUD + 方向/引用校验)
- [x] 业务系统隔离
- [x] 协议适配器：自定义 TCP、Modbus TCP（含 32/64 位多寄存器）、MQTT、OPC-UA
- [x] 实时数据采集引擎 (200ms) + 断线自动重连
- [x] 输入测点传播（OUTPUT 变化写到 INPUT 的关联通道）
- [x] Redis 实时缓存
- [x] TDengine 历史存储
- [x] WebSocket 实时推送
- [x] 前端五个页面 (仪表盘、业务、Channel 管理、测点管理、实时监控)
- [x] 数据导入导出（JSON / CSV）
- [x] 认证鉴权 (JWT，默认账号 admin / changeme，可通过环境变量覆盖)

## 后续迭代

- [ ] 历史数据查询页面（后端接口已就绪，前端未实现）
- [ ] 告警管理
- [ ] 数据库迁移工具（目前只支持空库，无就地升级路径）
