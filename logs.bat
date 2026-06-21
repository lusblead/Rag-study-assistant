@echo off
setlocal EnableExtensions

cd /d "%~dp0"

docker compose logs -f --tail=200
