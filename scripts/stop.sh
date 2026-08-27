#!/bin/bash
# 停止服务

echo "========================================="
echo "  Stopping SDNCustom Services"
echo "========================================="

# 停止前端
echo ""
echo "[1/3] Stopping frontend..."
if [ -f logs/frontend.pid ]; then
    PID=$(cat logs/frontend.pid)
    if kill -0 $PID 2>/dev/null; then
        kill $PID
        echo "Frontend stopped (PID: $PID)"
    else
        echo "Frontend process not found"
    fi
    rm -f logs/frontend.pid
else
    echo "Frontend PID file not found"
fi

# 停止后端
echo ""
echo "[2/3] Stopping backend..."
if [ -f logs/backend.pid ]; then
    PID=$(cat logs/backend.pid)
    if kill -0 $PID 2>/dev/null; then
        kill $PID
        echo "Backend stopped (PID: $PID)"
    else
        echo "Backend process not found"
    fi
    rm -f logs/backend.pid
else
    echo "Backend PID file not found"
fi

# 停止 Docker 服务
echo ""
echo "[3/3] Stopping Docker services..."
if command -v docker-compose &> /dev/null; then
    docker-compose down
elif command -v docker &> /dev/null; then
    docker compose down
else
    echo "Docker not found, skipping"
fi

echo ""
echo "========================================="
echo "  All services stopped!"
echo "========================================="
