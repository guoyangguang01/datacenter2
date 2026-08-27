#!/bin/bash
# 重启服务

echo "Restarting SDNCustom services..."
echo ""

./stop.sh
sleep 2
./start.sh
