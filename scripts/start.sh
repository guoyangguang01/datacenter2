#!/bin/bash
# 启动服务

set -e

echo "========================================="
echo "  Starting SDNCustom Services"
echo "========================================="

# 启动 Docker 服务 (Redis + TDengine)
echo ""
echo "[1/3] Starting Docker services..."
if command -v docker-compose &> /dev/null; then
    docker-compose up -d
elif command -v docker &> /dev/null; then
    docker compose up -d
else
    echo "Warning: Docker not found, skipping Redis and TDengine"
fi

# 等待服务启动
echo "Waiting for services to start..."
sleep 3

# 启动后端
echo ""
echo "[2/3] Starting backend server..."
if [ -d "C:/Users/guoya/.jdks/openjdk-23.0.2" ]; then
    export JAVA_HOME="C:/Users/guoya/.jdks/openjdk-23.0.2"
fi
cd sdncustom-server
nohup mvn spring-boot:run -q > ../logs/backend.log 2>&1 &
echo $! > ../logs/backend.pid
echo "Backend started (PID: $(cat ../logs/backend.pid))"
cd ..

# 等待后端启动
echo "Waiting for backend to start..."
sleep 10

# 启动前端
echo ""
echo "[3/3] Starting frontend dev server..."
cd sdncustom-web
nohup npm run dev > ../logs/frontend.log 2>&1 &
echo $! > ../logs/frontend.pid
echo "Frontend started (PID: $(cat ../logs/frontend.pid))"
cd ..

echo ""
echo "========================================="
echo "  All services started!"
echo "========================================="
echo ""
echo "  Backend:  http://localhost:8080"
echo "  Frontend: http://localhost:3000"
echo "  H2 Console: http://localhost:8080/h2-console"
echo ""
echo "  Logs:"
echo "    Backend:  logs/backend.log"
echo "    Frontend: logs/frontend.log"
echo ""
echo "  Stop: ./stop.sh"
echo "========================================="
