@echo off
setlocal EnableExtensions

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\logs.ps1"
