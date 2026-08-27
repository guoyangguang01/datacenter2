@echo off
REM 启动服务

echo =========================================
echo   Starting SDNCustom Services
echo =========================================

REM 设置 Java 环境
set JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

REM 创建日志目录
if not exist logs mkdir logs

REM 启动 Docker 服务
echo.
echo [1/3] Starting Docker services...
docker-compose up -d
if %ERRORLEVEL% NEQ 0 (
    echo Warning: Docker services failed to start
)

REM 等待服务启动
echo Waiting for services to start...
timeout /t 3 /nobreak > nul

REM 启动后端
echo.
echo [2/3] Starting backend server...
cd sdncustom-server
start /B mvn spring-boot:run -q > ..\logs\backend.log 2>&1
echo Backend started!
cd ..

REM 等待后端启动
echo Waiting for backend to start...
timeout /t 10 /nobreak > nul

REM 启动前端
echo.
echo [3/3] Starting frontend dev server...
cd sdncustom-web
start /B npm run dev > ..\logs\frontend.log 2>&1
echo Frontend started!
cd ..

echo.
echo =========================================
echo   All services started!
echo =========================================
echo.
echo   Backend:  http://localhost:8080
echo   Frontend: http://localhost:3000
echo   H2 Console: http://localhost:8080/h2-console
echo.
echo   Logs:
echo     Backend:  logs\backend.log
echo     Frontend: logs\frontend.log
echo.
echo   Stop: stop.bat
echo =========================================
