@echo off
rem 双击本文件启动 Bonsai（菜单里选模型，自动配视觉模型）
chcp 936 >nul 2>&1
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-bonsai.ps1" %*
