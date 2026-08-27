@echo off
chcp 65001 >nul
REM 停止服务

echo =========================================
echo   Stopping SDNCustom Services
echo =========================================

REM 停止 Java 进程 (后端)
echo.
echo [1/2] Stopping backend...
taskkill /F /FI "WINDOWTITLE eq *spring-boot*" > nul 2>&1
taskkill /F /FI "IMAGENAME eq java.exe" /FI "WINDOWTITLE eq *sdncustom*" > nul 2>&1
echo Backend stopped!

REM 停止 Node 进程 (前端)
echo.
echo [2/2] Stopping frontend...
taskkill /F /FI "IMAGENAME eq node.exe" /FI "WINDOWTITLE eq *vite*" > nul 2>&1
echo Frontend stopped!

REM 停止 Docker 服务
echo.
echo Stopping Docker services...
docker-compose down

echo.
echo =========================================
echo   All services stopped!
echo =========================================
