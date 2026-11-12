@echo off
start "SDNCustom Server" "%~dp0start-server.bat"
timeout /t 2 /nobreak >nul
start "SDNCustom Client" "%~dp0start-client.bat"
