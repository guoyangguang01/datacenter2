@echo off
chcp 65001 >nul 2>&1
REM Stop all mock servers
REM Usage: stop-mock-servers.bat

echo ========================================
echo   Stopping Mock Servers
echo ========================================
echo.

echo Stopping mock servers...

REM Stop processes by window title
taskkill /FI "WINDOWTITLE eq Mock TCP Server*" /F >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Mock TCP Server stopped
) else (
    echo [--] Mock TCP Server not running
)

taskkill /FI "WINDOWTITLE eq Mock Modbus Server*" /F >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Mock Modbus Server stopped
) else (
    echo [--] Mock Modbus Server not running
)

taskkill /FI "WINDOWTITLE eq Mock MQTT Client*" /F >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Mock MQTT Client stopped
) else (
    echo [--] Mock MQTT Client not running
)

taskkill /FI "WINDOWTITLE eq Mock OPC-UA Server*" /F >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Mock OPC-UA Server stopped
) else (
    echo [--] Mock OPC-UA Server not running
)

echo.
echo Done.
echo.
pause
