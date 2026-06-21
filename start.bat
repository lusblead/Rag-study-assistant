@echo off
setlocal EnableExtensions

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\portable-start.ps1"

if errorlevel 1 (
  echo.
  echo Startup failed. See logs.bat for details.
  pause
  exit /b 1
)

pause
