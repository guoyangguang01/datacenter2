#!/bin/bash
# 编译项目

set -e

echo "========================================="
echo "  Building SDNCustom"
echo "========================================="

# 检查 Java 版本
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Warning: Java 17+ recommended, current: $JAVA_VERSION"
    # 尝试使用指定的 JDK
    if [ -d "C:/Users/guoya/.jdks/openjdk-23.0.2" ]; then
        export JAVA_HOME="C:/Users/guoya/.jdks/openjdk-23.0.2"
        echo "Using JDK: $JAVA_HOME"
    fi
fi

# 编译后端
echo ""
echo "[1/2] Building backend..."
mvn clean install -DskipTests -q
echo "Backend build successful!"

# 安装前端依赖
echo ""
echo "[2/2] Installing frontend dependencies..."
cd sdncustom-web
npm install --silent
echo "Frontend dependencies installed!"

echo ""
echo "========================================="
echo "  Build completed!"
echo "========================================="
