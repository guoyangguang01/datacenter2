@echo off
chcp 65001 >nul 2>&1
REM Build and start mock servers
REM Usage: build-and-start.bat

echo ========================================
echo   Build and Start Mock Servers
echo ========================================
echo.

REM Set Java path (requires Java 17+)
set "JAVA_HOME=C:\Users\guoya\.jdks\openjdk-23.0.2"
set "JAVA_CMD=%JAVA_HOME%\bin\java"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [1/2] Building JAR package...
cd /d "%~dp0.."
call mvn package -pl sdncustom-common,sdncustom-protocol -DskipTests -q
if %ERRORLEVEL% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)
cd /d "%~dp0"
echo Build completed!

echo.
echo [2/2] Starting mock servers...
call start-mock-servers.bat
