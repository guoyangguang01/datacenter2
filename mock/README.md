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

### 3. 导入模拟数据

启动 SDNCustom 应用后，在测点管理页面点击"导入"按钮，选择 `mock-data.json` 文件导入预配置的通道和测点。

### 4. 连接通道

在通道管理页面，点击各通道的"连接"按钮连接到对应的模拟服务器。

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

如果需要单独启动某个模拟服务器，可以使用以下命令：

```bash
# 自定义 TCP 模拟服务器
java -cp <classpath> com.sdncustom.protocol.mock.MockTcpServer [port]

# Modbus TCP 模拟服务器
java -cp <classpath> com.sdncustom.protocol.mock.MockModbusTcpServer [port]

# MQTT 模拟客户端
java -cp <classpath> com.sdncustom.protocol.mock.MockMqttClient [broker_url]

# OPC-UA 模拟服务器
java -cp <classpath> com.sdncustom.protocol.mock.MockOpcUaServer [port]
```

## 注意事项

1. **MQTT 模拟客户端**需要先启动 MQTT Broker（如 Mosquitto）
2. **OPC-UA 模拟服务器**使用 Eclipse Milo SDK，首次启动可能需要较长时间初始化
3. 所有模拟服务器都会自动更新数据，模拟真实设备的实时变化
4. 模拟数据变化周期为 2-3 秒
5. 使用 `mock-data.json` 可以快速导入预配置的通道和测点

## 故障排除

### 端口被占用
如果端口被占用，可以修改启动命令的端口参数，同时修改 `mock-data.json` 中的连接配置。

### MQTT 连接失败
确保 MQTT Broker 已启动：
```bash
# Windows (Mosquitto)
net start mosquitto

# Linux
sudo systemctl start mosquitto
```

### OPC-UA 服务器启动失败
检查 Java 版本是否为 17+，Milo SDK 需要 Java 11+。

### 导入数据失败
确保 `mock-data.json` 文件格式正确，且通道配置中的端口与模拟服务器一致。
