@echo off
REM 启用 RabbitMQ MQTT 插件

echo ========================================
echo   RabbitMQ MQTT 插件配置脚本
echo ========================================
echo.

echo [1/3] 启动 RabbitMQ...
docker-compose up -d rabbitmq
timeout /t 10 /nobreak >nul

echo [2/3] 启用 MQTT 插件...
docker exec sdncustom-rabbitmq rabbitmq-plugins enable rabbitmq_mqtt
timeout /t 5 /nobreak >nul

echo [3/3] 重启 RabbitMQ 使插件生效...
docker-compose restart rabbitmq
timeout /t 10 /nobreak >nul

echo.
echo ========================================
echo   RabbitMQ MQTT 配置完成
echo ========================================
echo.
echo MQTT Broker: tcp://localhost:1883
echo Management UI: http://localhost:15672
echo 用户名: guest
echo 密码: guest
echo.
echo 现在可以启动 MQTT 模拟客户端了
echo.
pause
