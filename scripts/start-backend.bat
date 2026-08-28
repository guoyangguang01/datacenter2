@echo off
chcp 65001 >nul
REM 启动后端服务（需先运行 scripts\build.bat 构建 jar）

REM 设置 Java 环境（JAVA_HOME 指向 JDK 23）
set "JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0.."
cd sdncustom-server

if not exist target\sdncustom-server-1.0.0-SNAPSHOT.jar (
    echo ERROR: JAR 不存在，请先运行 scripts\build.bat 构建
    pause
    exit /b 1
)

java -jar target\sdncustom-server-1.0.0-SNAPSHOT.jar
