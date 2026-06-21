@echo off
setlocal EnableExtensions

cd /d "%~dp0"

docker compose ps
pause
