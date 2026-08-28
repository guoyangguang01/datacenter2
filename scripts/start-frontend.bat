@echo off
chcp 65001 >nul
REM 启动前端服务

cd /d "%~dp0.."
cd sdncustom-web
npm run dev
