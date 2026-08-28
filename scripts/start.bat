@echo off
chcp 65001 >nul
REM 启动服务 - 各服务在独立窗口运行，关闭窗口即可停止

echo =========================================
echo   Starting SDNCustom Services
echo =========================================

REM 设置 Java 环境
set JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2
set M2_HOME=D:\dev\software\apache-maven-3.9.9
set PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%

REM 启动后端 (独立窗口)
echo.
echo [1/2] Starting backend server...
start "SDNCustom-Backend" cmd /k "%~dp0start-backend.bat"
echo Backend started!

REM 启动前端 (独立窗口)
echo.
echo [2/2] Starting frontend dev server...
start "SDNCustom-Frontend" cmd /k "%~dp0start-frontend.bat"
echo Frontend started!

echo.
echo =========================================
echo   All services started!
echo =========================================
echo.
echo   Backend:  http://localhost:8080
echo   Frontend: http://localhost:3000
echo   H2 Console: http://localhost:8080/h2-console
echo.
echo   关闭对应的命令行窗口即可停止服务
echo =========================================
