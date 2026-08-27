@echo off
REM 重启服务

echo Restarting SDNCustom services...
echo.

call stop.bat
timeout /t 2 /nobreak > nul
call start.bat
