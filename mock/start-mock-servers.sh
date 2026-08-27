#!/bin/bash
# 启动所有模拟服务器
# 使用方法: ./start-mock-servers.sh

echo "========================================"
echo "  SDNCustom 模拟服务器启动脚本"
echo "========================================"
echo ""

# 设置 Java 路径 (请根据实际安装路径修改)
JAVA_CMD="java"
# JAVA_CMD="/path/to/java"

# 设置类路径
CP="../sdncustom-protocol/target/classes:../sdncustom-common/target/classes"
CP="$CP:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.17.0/jackson-databind-2.17.0.jar"
CP="$CP:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.17.0/jackson-annotations-2.17.0.jar"
CP="$CP:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.17.0/jackson-core-2.17.0.jar"
CP="$CP:$HOME/.m2/repository/org/projectlombok/lombok/1.18.34/lombok-1.18.34.jar"
CP="$CP:$HOME/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"
CP="$CP:$HOME/.m2/repository/ch/qos/logback/logback-classic/1.5.6/logback-classic-1.5.6.jar"
CP="$CP:$HOME/.m2/repository/ch/qos/logback/logback-core/1.5.6/logback-core-1.5.6.jar"
CP="$CP:$HOME/.m2/repository/org/eclipse/paho/org.eclipse.paho.mqttv5.client/1.2.5/org.eclipse.paho.mqttv5.client-1.2.5.jar"
CP="$CP:$HOME/.m2/repository/org/eclipse/milo/sdk-client/0.6.12/sdk-client-0.6.12.jar"
CP="$CP:$HOME/.m2/repository/org/eclipse/milo/sdk-server/0.6.12/sdk-server-0.6.12.jar"
CP="$CP:$HOME/.m2/repository/org/eclipse/milo/stack-core/0.6.12/stack-core-0.6.12.jar"

# 存储进程ID
PIDS=()

# 清理函数
cleanup() {
    echo ""
    echo "正在停止模拟服务器..."
    for pid in "${PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null
        fi
    done
    echo "模拟服务器已停止"
    exit 0
}

# 注册信号处理
trap cleanup SIGINT SIGTERM

echo "[1/4] 启动自定义 TCP 模拟服务器 (端口 9001)..."
$JAVA_CMD -cp "$CP" com.sdncustom.protocol.mock.MockTcpServer 9001 &
PIDS+=($!)
sleep 2

echo "[2/4] 启动 Modbus TCP 模拟服务器 (端口 5020)..."
$JAVA_CMD -cp "$CP" com.sdncustom.protocol.mock.MockModbusTcpServer 5020 &
PIDS+=($!)
sleep 2

echo "[3/4] 启动 MQTT 模拟客户端 (连接 tcp://localhost:1883)..."
echo "      注意: 需要先启动 MQTT Broker (如 Mosquitto)"
$JAVA_CMD -cp "$CP" com.sdncustom.protocol.mock.MockMqttClient tcp://localhost:1883 &
PIDS+=($!)
sleep 2

echo "[4/4] 启动 OPC-UA 模拟服务器 (端口 4840)..."
$JAVA_CMD -cp "$CP" com.sdncustom.protocol.mock.MockOpcUaServer 4840 &
PIDS+=($!)
sleep 2

echo ""
echo "========================================"
echo "  所有模拟服务器已启动"
echo "========================================"
echo ""
echo "自定义 TCP Server: localhost:9001"
echo "Modbus TCP Server:  localhost:5020"
echo "MQTT Client:        tcp://localhost:1883"
echo "OPC-UA Server:      opc.tcp://localhost:4840"
echo ""
echo "按 Ctrl+C 停止所有模拟服务器"

# 等待所有进程
wait
