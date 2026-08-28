@echo off
REM 构建并启动模拟服务器
REM 使用方法: build-and-start.bat

echo ========================================
echo   构建并启动模拟服务器
echo ========================================
echo.

REM 设置 Java 路径
set JAVA_CMD=java
REM set JAVA_CMD=C:\Users\guoya\.jdks\openjdk-23.0.2\bin\java

echo [1/2] 构建 JAR 包...
cd ..
call mvn package -pl sdncustom-common,sdncustom-protocol -DskipTests -q
if %ERRORLEVEL% neq 0 (
    echo 构建失败！
    pause
    exit /b 1
)
cd mock
echo 构建完成！

echo.
echo [2/2] 启动模拟服务器...
call start-mock-servers.bat
