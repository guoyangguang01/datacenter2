# SDNCustom - 设备测点数据集中分发系统设计文档

## 1. 项目概述

### 1.1 定位
通用物联网数据集中分发平台，以**测点**为核心，不关注设备模型。平台作为数据中枢，统一管理所有测点数据，不同客户端通过平台进行数据读写。

### 1.2 核心理念
- **测点是一等公民**：平台只关心测点，不关心设备
- **数据中枢模式**：平台是测点数据的唯一权威源
- **管道式数据同步**：Channel（管道）负责与外部系统的数据同步

### 1.3 规模目标
- 设备数量：100-1000（通过 Channel 间接支持）
- 测点数量：数万级
- 采集频率：200ms
- 并发客户端：数十个

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                    React Frontend                       │
│  React 18 + TypeScript + Ant Design + Zustand          │
│  WebSocket Client ← 实时数据推送                         │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTP REST + WebSocket
┌──────────────────────┴──────────────────────────────────┐
│                 Spring Boot Server                       │
│                                                         │
│  ┌─────────────┐ ┌─────────────┐ ┌───────────────────┐ │
│  │ REST API    │ │ WebSocket   │ │ Protocol Engine   │ │
│  │ Controller  │ │ Handler     │ │ 协议引擎           │ │
│  └─────────────┘ └─────────────┘ └───────────────────┘ │
│                                                         │
│  ┌─────────────┐ ┌─────────────┐ ┌───────────────────┐ │
│  │ Channel Svc │ │ Point Svc   │ │ Acquisition Svc   │ │
│  │ 管道服务     │ │ 测点服务     │ │ 采集服务           │ │
│  └─────────────┘ └─────────────┘ └───────────────────┘ │
│                                                         │
│  ┌─────────────┐ ┌─────────────┐ ┌───────────────────┐ │
│  │ Dist Svc    │ │ History Svc │ │ Cache Svc         │ │
│  │ 分发服务     │ │ 历史服务     │ │ 缓存服务           │ │
│  └─────────────┘ └─────────────┘ └───────────────────┘ │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────────────┐
│                    Storage Layer                         │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐  │
│  │ H2/SQLite    │ │   TDengine   │ │     Redis      │  │
│  │ 配置数据     │ │  时序历史数据  │ │   实时数据缓存  │  │
│  └──────────────┘ └──────────────┘ └────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
外部系统 ──→ Channel ──→ 采集服务 ──→ Redis(实时) + TDengine(历史)
                                ↓
                          分发服务 ──→ WebSocket ──→ 前端客户端

前端客户端 ──→ REST API ──→ 测点服务 ──→ 写入 Redis
                                ↓
                          Channel ──→ 外部系统
```

---

## 3. 核心数据模型

### 3.1 Channel（管道）

Channel 是与外部系统的连接通道，负责数据同步。

```java
public class Channel {
    private String channelId;          // 唯一标识
    private String channelName;        // 名称
    private ProtocolType protocolType; // 协议类型 (CUSTOM_TCP, MODBUS_TCP, MQTT, OPCUA)
    private ChannelDirection direction;// 方向 (READ_ONLY, WRITE_ONLY, READ_WRITE)
    private String connectionConfig;   // 连接配置 (JSON: ip, port, params)
    private ChannelStatus status;      // 状态 (DISCONNECTED, CONNECTED, ERROR)
    private boolean autoConnect;       // 系统启动时是否自动连接
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 3.2 MeasurementPoint（测点）

测点是平台的一等公民，是数据的最小单元。

```java
public class MeasurementPoint {
    private String pointId;            // 唯一标识
    private String pointName;          // 名称
    private String channelId;          // 所属 Channel
    private String address;            // 协议地址 (由协议适配器解析)
    private PointDataType dataType;    // 数据类型
    private String unit;               // 单位
    private double scaleFactor;        // 缩放因子
    private double offset;             // 偏移量
    private double deadBand;           // 死区 (变化量阈值)
    private boolean writable;          // 是否可写
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 3.3 PointValue（测点值）

测点的当前值，存储在 Redis 中。

```java
public class PointValue {
    private String pointId;
    private Object value;              // 当前值
    private PointQuality quality;      // 数据质量
    private String sourceChannelId;    // 写入来源 Channel
    private long timestamp;            // 更新时间戳
}
```

### 3.4 PointHistory（历史值）

测点的历史记录，存储在 TDengine 中。

```java
public class PointHistory {
    private String pointId;
    private Object value;
    private PointQuality quality;
    private String sourceChannelId;
    private long timestamp;
}
```

### 3.5 枚举定义

```java
public enum ProtocolType {
    CUSTOM_TCP,    // 自定义 TCP 协议
    MODBUS_TCP,    // Modbus TCP (预留)
    MQTT,          // MQTT (预留)
    OPCUA          // OPC-UA (预留)
}

public enum ChannelDirection {
    READ_ONLY,     // 只读：从外部系统采集数据到平台
    WRITE_ONLY,    // 只写：从平台写数据到外部系统
    READ_WRITE     // 读写：双向同步
}

public enum ChannelStatus {
    DISCONNECTED,  // 未连接
    CONNECTED,     // 已连接
    ERROR          // 错误
}

public enum PointDataType {
    BOOL,          // 布尔
    INT16,         // 16位整数
    INT32,         // 32位整数
    FLOAT32,       // 32位浮点
    FLOAT64,       // 64位浮点
    STRING         // 字符串
}

public enum PointQuality {
    GOOD,          // 正常
    BAD,           // 坏值
    UNCERTAIN,     // 不确定
    COMM_LOST      // 通信中断
}
```

---

## 4. Channel 设计

### 4.1 数据流向

| 方向 | 说明 |
|------|------|
| READ_ONLY | Channel 从外部系统读取数据，写入平台测点 |
| WRITE_ONLY | Channel 从平台读取测点值，写入外部系统 |
| READ_WRITE | 双向同步 |

### 4.2 连接生命周期

- **系统启动**：自动连接 `autoConnect=true` 的 Channel（异步非阻塞，不阻塞系统启动）
- **手动控制**：支持通过 API 手动连接/断开单个 Channel
- **故障处理**：Channel 断开后保持断开状态，不自动重连
- **状态标记**：断开时，该 Channel 下所有测点质量标记为 `COMM_LOST`，值保持最后有效值

### 4.3 多 Channel 写冲突

当多个 Channel 同时写入同一测点时，采用 **Last Write Wins** 策略：
- 以时间戳最新的写入为准
- 平台记录每次写入的来源 Channel
- 客户端可查看当前值的来源

---

## 5. 协议适配层

### 5.1 适配器接口

```java
public interface ProtocolAdapter {
    /**
     * 连接到外部系统
     */
    void connect(Channel channel);

    /**
     * 断开连接
     */
    void disconnect();

    /**
     * 读取测点值
     */
    PointValue readPoint(MeasurementPoint point);

    /**
     * 写入测点值
     */
    void writePoint(MeasurementPoint point, Object value);

    /**
     * 批量读取测点值
     */
    List<PointValue> readPoints(List<MeasurementPoint> points);

    /**
     * 是否已连接
     */
    boolean isConnected();
}
```

### 5.2 协议注册中心

```java
public class ProtocolRegistry {
    private Map<ProtocolType, ProtocolAdapter> adapters;

    public void register(ProtocolType type, ProtocolAdapter adapter);
    public ProtocolAdapter getAdapter(ProtocolType type);
}
```

### 5.3 自定义 TCP 协议格式（第一期）

**报文结构：**
```
┌──────────────┬──────────────┬──────────────────────┐
│ Length (4B)  │ Command (1B) │ JSON Body (变长)      │
│ 大端序       │ 命令码        │ UTF-8 编码            │
└──────────────┴──────────────┴──────────────────────┘
```

**命令码定义：**
| 命令码 | 方向 | 说明 |
|--------|------|------|
| 0x01 | C→S | 读取测点值请求 |
| 0x02 | S→C | 读取测点值响应 |
| 0x03 | C→S | 写入测点值请求 |
| 0x04 | S→C | 写入测点值响应 |
| 0x05 | S→C | 测点值变化推送 |
| 0x10 | C→S | 心跳请求 |
| 0x11 | S→C | 心跳响应 |

**示例报文（读取请求）：**
```json
{
    "pointIds": ["point_001", "point_002"]
}
```

**示例报文（值推送）：**
```json
{
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

---

## 6. 数据分发

### 6.1 实时推送机制

**触发条件：**
- 测点值变化量超过 `deadBand` 阈值时推送
- 客户端可请求"强制全量刷新"

**WebSocket 订阅模型：**
- 客户端按 Channel 订阅
- 订阅一个 Channel 即接收该 Channel 下所有测点的变化推送
- 支持批量订阅/取消订阅

**WebSocket 消息格式：**

```json
// 客户端 → 服务端：订阅
{
    "action": "subscribe",
    "channelIds": ["ch_001", "ch_002"]
}

// 客户端 → 服务端：取消订阅
{
    "action": "unsubscribe",
    "channelIds": ["ch_001"]
}

// 客户端 → 服务端：强制刷新
{
    "action": "refresh",
    "channelIds": ["ch_001"]
}

// 服务端 → 客户端：数据推送
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

// 服务端 → 客户端：Channel 状态变化
{
    "type": "channel_status",
    "channelId": "ch_001",
    "status": "DISCONNECTED"
}
```

### 6.2 数据写入流程

```
客户端 → REST API → 测点服务 → Redis(更新当前值)
                              → 历史服务(记录历史)
                              → 分发服务(推送给订阅者)
                              → Channel(写入外部系统，如果 direction 包含写)
```

---

## 7. 存储设计

### 7.1 配置数据（H2/SQLite）

存储 Channel 和 MeasurementPoint 的配置信息。

**Channel 表：**
```sql
CREATE TABLE channel (
    channel_id VARCHAR(64) PRIMARY KEY,
    channel_name VARCHAR(128) NOT NULL,
    protocol_type VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    connection_config TEXT,
    auto_connect BOOLEAN DEFAULT TRUE,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
```

**MeasurementPoint 表：**
```sql
CREATE TABLE measurement_point (
    point_id VARCHAR(64) PRIMARY KEY,
    point_name VARCHAR(128) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    address VARCHAR(256) NOT NULL,
    data_type VARCHAR(16) NOT NULL,
    unit VARCHAR(32),
    scale_factor DOUBLE DEFAULT 1.0,
    offset_value DOUBLE DEFAULT 0.0,
    dead_band DOUBLE DEFAULT 0.0,
    writable BOOLEAN DEFAULT FALSE,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    FOREIGN KEY (channel_id) REFERENCES channel(channel_id)
);
```

### 7.2 实时数据（Redis）

- Key 格式：`point:{pointId}`
- Value：PointValue 的 JSON 序列化
- 无 TTL，持久保留

### 7.3 历史数据（TDengine）

- 超级表：`point_history`
- 子表：按 pointId 分表
- 字段：value, quality, source_channel_id, timestamp
- 保留策略：原始数据不设过期（由运维根据存储空间管理）

---

## 8. REST API 设计

### 8.1 Channel API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/channels | 查询所有 Channel |
| GET | /api/channels/{id} | 查询单个 Channel |
| POST | /api/channels | 创建 Channel |
| PUT | /api/channels/{id} | 更新 Channel |
| DELETE | /api/channels/{id} | 删除 Channel |
| POST | /api/channels/{id}/connect | 连接 Channel |
| POST | /api/channels/{id}/disconnect | 断开 Channel |

### 8.2 MeasurementPoint API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/points | 查询所有测点（支持按 channelId 过滤）|
| GET | /api/points/{id} | 查询单个测点 |
| POST | /api/points | 创建测点 |
| PUT | /api/points/{id} | 更新测点 |
| DELETE | /api/points/{id} | 删除测点 |
| GET | /api/points/{id}/value | 获取测点当前值 |
| PUT | /api/points/{id}/value | 写入测点值 |

### 8.3 History API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/points/{id}/history | 查询历史数据（参数：startTime, endTime, page, size）|

---

## 9. 前端设计（第一期）

### 9.1 技术栈

- React 18 + TypeScript
- Ant Design 5.x
- Zustand（状态管理）
- WebSocket（实时数据）

### 9.2 页面规划

| 页面 | 功能 |
|------|------|
| Channel 管理 | Channel 列表、新增/编辑/删除、连接/断开操作 |
| 测点管理 | 测点列表、新增/编辑/删除、按 Channel 过滤 |
| 实时看板 | 订阅 Channel、实时显示测点值、数据质量标记 |

---

## 10. 项目结构

```
sdncustom/
├── pom.xml
│
├── sdncustom-common/                 # 公共模块
│   └── src/main/java/com/sdncustom/common/
│       ├── model/                    # 实体类
│       │   ├── Channel.java
│       │   ├── MeasurementPoint.java
│       │   ├── PointValue.java
│       │   └── enums/               # 枚举
│       ├── dto/                      # 数据传输对象
│       └── exception/                # 自定义异常
│
├── sdncustom-protocol/               # 协议适配层
│   └── src/main/java/com/sdncustom/protocol/
│       ├── ProtocolAdapter.java      # 适配器接口
│       ├── ProtocolRegistry.java     # 注册中心
│       └── tcp/                      # 自定义 TCP 实现
│
├── sdncustom-server/                 # 服务端主程序
│   └── src/main/java/com/sdncustom/server/
│       ├── config/                   # 配置类
│       ├── controller/               # REST API
│       ├── websocket/                # WebSocket
│       ├── service/                  # 业务服务
│       └── repository/               # 数据访问
│
└── sdncustom-web/                    # 前端项目
    └── src/
        ├── pages/                    # 页面组件
        ├── components/               # 通用组件
        ├── stores/                   # Zustand 状态
        ├── services/                 # API 调用
        └── types/                    # TypeScript 类型
```

---

## 11. 第一期范围

### 包含
- Channel 管理（CRUD + 连接/断开）
- 测点管理（CRUD）
- 自定义 TCP 协议适配器
- 实时数据采集引擎（200ms）
- Redis 实时缓存
- TDengine 历史存储
- WebSocket 实时推送
- 前端三个页面（Channel 管理、测点管理、实时看板）

### 不包含（后续迭代）
- Modbus TCP/RTU 适配器
- MQTT 适配器
- OPC-UA 适配器
- 历史数据查询页面
- 认证鉴权（JWT）
- 数据聚合/降采样
- 告警管理
