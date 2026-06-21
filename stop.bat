@echo off
setlocal EnableExtensions

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\portable-stop.ps1"
pause
