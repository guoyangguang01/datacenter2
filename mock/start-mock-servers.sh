#!/bin/bash
# 启动所有模拟服务器（JAR 方式）
# 使用方法: ./start-mock-servers.sh

echo "========================================"
echo "  SDNCustom 模拟服务器启动脚本"
echo "========================================"
echo ""

# 设置 Java 路径 (请根据实际安装路径修改)
JAVA_CMD="java"
# JAVA_CMD="/path/to/java"

# 设置 JAR 路径（包含所有依赖的 fat JAR）
JAR_PATH="../sdncustom-protocol/target/sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

# 检查 JAR 是否存在
if [ ! -f "$JAR_PATH" ]; then
    echo "错误: 找不到 JAR 文件，请先执行 mvn package"
    echo "路径: $JAR_PATH"
    exit 1
fi

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
$JAVA_CMD -cp "$JAR_PATH" com.sdncustom.protocol.mock.MockTcpServer 9001 &
PIDS+=($!)
sleep 2

echo "[2/4] 启动 Modbus TCP 模拟服务器 (端口 5020)..."
$JAVA_CMD -cp "$JAR_PATH" com.sdncustom.protocol.mock.MockModbusTcpServer 5020 &
PIDS+=($!)
sleep 2

echo "[3/4] 启动 MQTT 模拟客户端 (连接 tcp://localhost:1883)..."
echo "      使用 Mosquitto MQTT Broker (匿名连接)"
$JAVA_CMD -cp "$JAR_PATH" com.sdncustom.protocol.mock.MockMqttClient tcp://localhost:1883 &
PIDS+=($!)
sleep 2

echo "[4/4] 启动 OPC-UA 模拟服务器 (端口 4840)..."
$JAVA_CMD -cp "$JAR_PATH" com.sdncustom.protocol.mock.MockOpcUaServer 4840 &
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
