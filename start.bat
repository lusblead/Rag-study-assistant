@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo.
echo ==========================================
echo   RAG Study Assistant - One-click Start
echo ==========================================
echo.

if not exist ".env" (
  if exist ".env.example" (
    copy ".env.example" ".env" >nul
    echo Created .env from .env.example.
  )
)

where docker >nul 2>nul
if errorlevel 1 (
  echo Docker was not found. Please install Docker Desktop first:
  echo https://www.docker.com/products/docker-desktop/
  echo.
  pause
  exit /b 1
)

docker info >nul 2>nul
if errorlevel 1 (
  echo Docker is not running. Trying to start Docker Desktop...
  if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" (
    start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
  )
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\wait-docker.ps1"
  if errorlevel 1 (
    echo Docker did not become ready in time.
    pause
    exit /b 1
  )
)

docker compose version >nul 2>nul
if errorlevel 1 (
  echo Docker Compose was not found. Please update Docker Desktop.
  echo.
  pause
  exit /b 1
)

echo Starting services. The first run may take several minutes while Docker pulls images and builds the app.
docker compose up -d --build
if errorlevel 1 (
  echo.
  echo Startup failed. Run logs.bat to inspect service logs.
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\wait-services.ps1"
if errorlevel 1 (
  echo.
  echo Services were started, but readiness checks did not finish in time.
  echo You can still try opening http://localhost:5173 after another minute.
  echo.
  pause
  exit /b 1
)

echo.
echo Application is ready:
echo   Frontend: http://localhost:5173
echo   Backend:  http://localhost:8080
echo.
start "" "http://localhost:5173"
pause
