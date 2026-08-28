#!/bin/bash
# 构建并启动模拟服务器
# 使用方法: ./build-and-start.sh

echo "========================================"
echo "  构建并启动模拟服务器"
echo "========================================"
echo ""

echo "[1/2] 构建 JAR 包..."
cd ..
mvn package -pl sdncustom-common,sdncustom-protocol -DskipTests -q
if [ $? -ne 0 ]; then
    echo "构建失败！"
    exit 1
fi
cd mock
echo "构建完成！"

echo ""
echo "[2/2] 启动模拟服务器..."
./start-mock-servers.sh
