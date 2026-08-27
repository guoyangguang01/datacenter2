@echo off
REM 启动所有模拟服务器
REM 使用方法: start-mock-servers.bat

echo ========================================
echo   SDNCustom 模拟服务器启动脚本
echo ========================================
echo.

REM 设置 Java 路径 (请根据实际安装路径修改)
set JAVA_CMD=java
REM set JAVA_CMD=C:\Users\guoya\.jdks\openjdk-23.0.2\bin\java

REM 设置类路径
set CP=..\sdncustom-protocol\target\classes;..\sdncustom-common\target\classes
set CP=%CP%;%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-databind\2.17.0\jackson-databind-2.17.0.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-annotations\2.17.0\jackson-annotations-2.17.0.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.17.0\jackson-core-2.17.0.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\projectlombok\lombok\1.18.34\lombok-1.18.34.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\slf4j\slf4j-api\2.0.13\slf4j-api-2.0.13.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-classic\1.5.6\logback-classic-1.5.6.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\ch\qos\logback\logback-core\1.5.6\logback-core-1.5.6.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\eclipse\paho\org.eclipse.paho.mqttv5.client\1.2.5\org.eclipse.paho.mqttv5.client-1.2.5.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\eclipse\milo\sdk-client\0.6.12\sdk-client-0.6.12.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\eclipse\milo\sdk-server\0.6.12\sdk-server-0.6.12.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\eclipse\milo\stack-core\0.6.12\stack-core-0.6.12.jar

echo [1/4] 启动自定义 TCP 模拟服务器 (端口 9001)...
start "Mock TCP Server" %JAVA_CMD% -cp "%CP%" com.sdncustom.protocol.mock.MockTcpServer 9001
timeout /t 2 /nobreak >nul

echo [2/4] 启动 Modbus TCP 模拟服务器 (端口 5020)...
start "Mock Modbus Server" %JAVA_CMD% -cp "%CP%" com.sdncustom.protocol.mock.MockModbusTcpServer 5020
timeout /t 2 /nobreak >nul

echo [3/4] 启动 MQTT 模拟客户端 (连接 tcp://localhost:1883)...
echo       注意: 需要先启动 MQTT Broker (如 Mosquitto)
start "Mock MQTT Client" %JAVA_CMD% -cp "%CP%" com.sdncustom.protocol.mock.MockMqttClient tcp://localhost:1883
timeout /t 2 /nobreak >nul

echo [4/4] 启动 OPC-UA 模拟服务器 (端口 4840)...
start "Mock OPC-UA Server" %JAVA_CMD% -cp "%CP%" com.sdncustom.protocol.mock.MockOpcUaServer 4840
timeout /t 2 /nobreak >nul

echo.
echo ========================================
echo   所有模拟服务器已启动
echo ========================================
echo.
echo 自定义 TCP Server: localhost:9001
echo Modbus TCP Server:  localhost:5020
echo MQTT Client:        tcp://localhost:1883
echo OPC-UA Server:      opc.tcp://localhost:4840
echo.
echo 按任意键停止所有模拟服务器...
pause >nul

echo 正在停止模拟服务器...
taskkill /FI "WINDOWTITLE eq Mock TCP Server*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Mock Modbus Server*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Mock MQTT Client*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Mock OPC-UA Server*" /F >nul 2>&1
echo 模拟服务器已停止
