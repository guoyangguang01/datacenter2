@echo off
chcp 65001 >nul
REM 编译后端

REM 切换到项目根目录
cd /d %~dp0..

echo =========================================
echo   Building SDNCustom Backend
echo =========================================

REM 设置 Java 环境
set JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2
set M2_HOME=D:\dev\software\apache-maven-3.9.9
set PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%

REM 编译后端
echo.
call mvn clean install -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo =========================================
echo   Build completed!
echo =========================================
