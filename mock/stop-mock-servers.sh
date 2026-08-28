#!/bin/bash
# 停止所有模拟服务器
# 使用方法: ./stop-mock-servers.sh

echo "========================================"
echo "  停止模拟服务器"
echo "========================================"
echo ""

echo "正在停止模拟服务器..."

# 查找并停止模拟服务器进程
PIDS=$(ps aux | grep -E "MockTcpServer|MockModbusTcpServer|MockMqttClient|MockOpcUaServer" | grep -v grep | awk '{print $2}')

if [ -z "$PIDS" ]; then
    echo "没有找到运行中的模拟服务器"
else
    for PID in $PIDS; do
        PROCESS=$(ps -p $PID -o comm= 2>/dev/null)
        kill $PID 2>/dev/null
        if [ $? -eq 0 ]; then
            echo "[√] 进程 $PID 已停止"
        else
            echo "[×] 无法停止进程 $PID"
        fi
    done
fi

echo ""
echo "模拟服务器停止完成"
