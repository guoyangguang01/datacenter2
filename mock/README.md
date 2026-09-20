# SDNCustom 模拟服务器

本目录包含用于测试和演示的模拟服务器，支持所有协议类型的全场景模拟。

## 模拟服务器列表

| 服务器 | 端口 | 协议 | 说明 |
|--------|------|------|------|
| MockTcpServer | 9002 | 自定义 TCP | 模拟自定义二进制协议设备 |
| MockModbusTcpServer | 5020 | Modbus TCP | 模拟 Modbus 设备（寄存器/线圈） |
| MockMqttClient | 1883 | MQTT | 模拟 MQTT 传感器数据发布 |
| MockOpcUaServer | 4840 | OPC-UA | 模拟 OPC-UA 服务器节点 |

## 快速开始

### 1. 编译项目

```bash
cd /path/to/SDNCustom
mvn clean compile
```

### 2. 启动模拟服务器

**Windows:**
```bash
cd mock
start-mock-servers.bat
```

**Linux/Mac:**
```bash
cd mock
chmod +x start-mock-servers.sh
./start-mock-servers.sh
```

### 3. 导入业务、通道与测点

启动 SDNCustom 应用后，在**测点管理**页面点击「导入数据」，选择 `mock-data.json` —— 它含
`businesses` / `channels` / `points` 三段，业务与通道会自动创建（已存在的跳过），随后创建测点。

> 若改为手工建通道，则顺序不能颠倒：测点通过 `channelId` 关联通道，导入时通道必须已存在或随同
> 一次 payload 提供，否则会整体失败并点名缺失的通道。

> `mock-channels.json` 是早期版本的遗留文件——应用**没有**「只导入通道」的入口（通道页没有导入按钮，
> 后端也没有 `POST /api/channels/import`）。要建通道就用 `mock-data.json` 的 `channels` 段，或在通道页手工建。

### 4. 连接通道

在通道管理页面，点击各通道的"连接"按钮连接到对应的模拟服务器（导入的通道都是 `autoConnect=false`，不会自动连）。

## 模拟数据说明

### 自定义 TCP 模拟数据

| 测点ID | 数据类型 | 说明 | 变化规律 |
|--------|----------|------|----------|
| bool_001 | BOOL | 设备运行状态 | 随机切换 |
| bool_002 | BOOL | 报警信号 | 随机切换 |
| int16_001 | INT16 | 温度设定值 | ±5 波动 |
| int32_001 | INT32 | 生产计数 | ±50 波动 |
| float32_001 | FLOAT32 | 环境温度 | ±1°C 波动 |
| float64_001 | FLOAT64 | 精确测量值 | ±0.05 波动 |
| string_001 | STRING | 设备型号 | 固定值 |
| string_002 | STRING | 运行状态 | 随机切换 |

### Modbus TCP 模拟数据

| 寄存器地址 | 说明 | 单位 | 变化规律 |
|------------|------|------|----------|
| 40001 | 管道温度 | °C | ±10 波动 |
| 40002 | 管道压力 | kPa | ±10 波动 |
| 40003 | 主管流量 | L/min | ±250 波动 |
| 40004 | 电源电压 | V | 固定 380V |
| 40005 | 电机转速 | RPM | ±50 波动 |
| 40006 | 环境湿度 | % | ±150 波动 |
| 线圈 1-3 | 泵/阀门状态 | - | 随机切换 |

### MQTT 模拟数据

| Topic | 数据类型 | 说明 | 变化规律 |
|-------|----------|------|----------|
| sensors/temperature/* | FLOAT64 | 温度传感器 | ±2°C 波动 |
| sensors/humidity/* | FLOAT64 | 湿度传感器 | ±5% 波动 |
| sensors/pressure/* | FLOAT64 | 压力传感器 | ±2.5 kPa 波动 |
| devices/pump01/* | STRING/INT32 | 泵状态 | 90% 运行率 |
| valves/* | BOOL | 阀门状态 | 随机切换 |
| meters/emeter01/* | FLOAT64 | 电表数据 | 实时计算 |
| counters/* | INT32 | 计数器 | 递增 |
| alarms/* | STRING | 告警 | 80% 正常 |

### OPC-UA 模拟数据

| 节点路径 | 数据类型 | 说明 | 变化规律 |
|----------|----------|------|----------|
| Temperature/* | FLOAT64 | 温度传感器 | ±2-3°C 波动 |
| Pressure/* | FLOAT64 | 压力传感器 | ±2.5-5 kPa 波动 |
| Flow/* | FLOAT64 | 流量传感器 | ±5 L/min 波动 |
| Level/* | FLOAT64 | 液位传感器 | ±5% 波动 |
| Status/* | BOOL | 设备状态 | 随机切换 |
| Counter/* | INT32 | 计数器 | 递增 |
| Alarm/* | STRING | 告警状态 | 80% 正常 |
| Mode/* | STRING | 运行模式 | 随机切换 |

## 单独启动模拟服务器

### 前提：构建 Fat JAR

单独启动前需先构建 protocol 模块，生成包含所有依赖的 fat JAR：

```bash
cd /path/to/SDNCustom
mvn clean package -pl sdncustom-protocol -am -DskipTests
```

> JAR 路径：`sdncustom-protocol/target/sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar`

### 启动命令

**Windows：**

```bat
set JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2

REM 自定义 TCP 模拟服务器（默认端口 9002）
java -cp sdncustom-protocol\target\sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockTcpServer 9002

REM Modbus TCP 模拟服务器（默认端口 5020）
java -cp sdncustom-protocol\target\sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockModbusTcpServer 5020

REM MQTT 模拟客户端（需先启动 Mosquitto）
java -cp sdncustom-protocol\target\sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockMqttClient tcp://localhost:1883

REM OPC-UA 模拟服务器（默认端口 4840）
java -cp sdncustom-protocol\target\sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockOpcUaServer 4840
```

**Linux/Mac：**

```bash
export JAVA_HOME=/path/to/jdk-23

# 自定义 TCP 模拟服务器（默认端口 9002）
java -cp sdncustom-protocol/target/sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockTcpServer 9002

# Modbus TCP 模拟服务器（默认端口 5020）
java -cp sdncustom-protocol/target/sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockModbusTcpServer 5020

# MQTT 模拟客户端（需先启动 Mosquitto）
java -cp sdncustom-protocol/target/sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockMqttClient tcp://localhost:1883

# OPC-UA 模拟服务器（默认端口 4840）
java -cp sdncustom-protocol/target/sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.sdncustom.protocol.mock.MockOpcUaServer 4840
```

### 参数说明

| 模拟器 | 参数 | 默认值 | 说明 |
|--------|------|--------|------|
| MockTcpServer | port | 9002 | TCP 监听端口 |
| MockModbusTcpServer | port | 5020 | Modbus TCP 监听端口 |
| MockMqttClient | broker_url | tcp://localhost:1883 | MQTT Broker 地址 |
| MockOpcUaServer | port | 4840 | OPC-UA 监听端口 |

## 注意事项

1. **MQTT 模拟客户端**需要先启动 MQTT Broker（如 Mosquitto）
2. **OPC-UA 模拟服务器**使用 Eclipse Milo SDK，首次启动可能需要较长时间初始化
3. 所有模拟服务器都会自动更新数据，模拟真实设备的实时变化
4. 模拟数据变化周期为 2-3 秒
5. 引导是一条命令：导入 `mock-data.json`（业务+通道+测点）→ 逐个连接通道

## 故障排除

### 端口被占用
如果端口被占用，可以修改启动命令的端口参数，同时修改 `mock-data.json` 中对应通道的 `connectionConfig`。

### MQTT 连接失败
确保 MQTT Broker 已启动：
```bash
# Windows (Mosquitto)
net start mosquitto

# Linux
sudo systemctl start mosquitto
```

### OPC-UA 服务器启动失败
检查 Java 版本是否为 23（本项目根 pom 固定 `<java.version>23</java.version>`），Milo SDK 需要 Java 11+。

### 导入数据失败
报错会点名缺失的通道——确认 `mock-data.json` 的 `channels` 段里包含测点引用的全部 `channelId`；
再检查各通道 `connectionConfig` 里的端口与模拟服务器一致（TCP 9002 / Modbus 5020 / MQTT 1883 / OPC-UA 4840）。
