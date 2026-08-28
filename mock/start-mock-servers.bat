@echo off
chcp 65001 >nul 2>&1
REM Start all mock servers (JAR mode)
REM Usage: start-mock-servers.bat

echo ========================================
echo   SDNCustom Mock Servers Startup
echo ========================================
echo.

REM Set Java path (requires Java 17+)
set "JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2"
set "JAVA_CMD=%JAVA_HOME%\bin\java"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Set JAR path (fat JAR with all dependencies)
set "JAR_PATH=%~dp0..\sdncustom-protocol\target\sdncustom-protocol-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

REM Check if JAR exists
if not exist "%JAR_PATH%" (
    echo ERROR: JAR file not found, please run mvn package first
    echo Path: %JAR_PATH%
    pause
    exit /b 1
)

echo [1/4] Starting Custom TCP Mock Server (port 9002)...
start "Mock TCP Server" "%JAVA_CMD%" -cp "%JAR_PATH%" com.sdncustom.protocol.mock.MockTcpServer 9002
timeout /t 2 /nobreak >nul

echo [2/4] Starting Modbus TCP Mock Server (port 5020)...
start "Mock Modbus Server" "%JAVA_CMD%" -cp "%JAR_PATH%" com.sdncustom.protocol.mock.MockModbusTcpServer 5020
timeout /t 2 /nobreak >nul

echo [3/4] Starting MQTT Mock Client (connect tcp://localhost:1883)...
echo       Using Mosquitto MQTT Broker (anonymous)
start "Mock MQTT Client" "%JAVA_CMD%" -cp "%JAR_PATH%" com.sdncustom.protocol.mock.MockMqttClient tcp://localhost:1883
timeout /t 2 /nobreak >nul

echo [4/4] Starting OPC-UA Mock Server (port 4840)...
start "Mock OPC-UA Server" "%JAVA_CMD%" -cp "%JAR_PATH%" com.sdncustom.protocol.mock.MockOpcUaServer 4840
timeout /t 2 /nobreak >nul

echo.
echo ========================================
echo   All Mock Servers Started
echo ========================================
echo.
echo Custom TCP Server: localhost:9002
echo Modbus TCP Server:  localhost:5020
echo MQTT Client:        tcp://localhost:1883
echo OPC-UA Server:      opc.tcp://localhost:4840
echo.
echo Press any key to stop all mock servers...
pause >nul

echo Stopping mock servers...
taskkill /FI "WINDOWTITLE eq Mock TCP Server*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Mock Modbus Server*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Mock MQTT Client*" /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Mock OPC-UA Server*" /F >nul 2>&1
echo Mock servers stopped
