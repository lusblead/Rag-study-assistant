@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo Stopping RAG Study Assistant services...
docker compose down
echo.
echo Stopped. Data volumes are preserved.
pause
