@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo Starting the current source tree in full Docker mode.
echo Portable no-Docker mode is not available from this entry point.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1"

if errorlevel 1 (
  echo.
  echo Startup failed. This entry point requires Docker; inspect the message above.
  echo If containers were created, use logs.bat for service logs.
  pause
  exit /b 1
)

pause
