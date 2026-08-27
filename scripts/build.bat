@echo off
REM 编译项目

echo =========================================
echo   Building SDNCustom
echo =========================================

REM 设置 Java 环境
set JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

REM 编译后端
echo.
echo [1/2] Building backend...
call mvn clean install -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo Backend build failed!
    exit /b 1
)
echo Backend build successful!

REM 安装前端依赖
echo.
echo [2/2] Installing frontend dependencies...
cd sdncustom-web
call npm install --silent
if %ERRORLEVEL% NEQ 0 (
    echo Frontend install failed!
    exit /b 1
)
echo Frontend dependencies installed!
cd ..

echo.
echo =========================================
echo   Build completed!
echo =========================================
