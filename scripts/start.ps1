$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

function Test-Command([string]$Command) {
    $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
}

function Test-HttpReady([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

function Get-LanIp {
    try {
        $ip = Get-NetIPAddress -AddressFamily IPv4 |
            Where-Object {
                $_.IPAddress -notlike "127.*" -and
                $_.IPAddress -notlike "169.254.*" -and
                $_.IPAddress -notlike "172.17.*"
            } |
            Select-Object -First 1 -ExpandProperty IPAddress
        return $ip
    } catch {
        return ""
    }
}

function Wait-Services {
    $deadline = (Get-Date).AddMinutes(4)
    $frontendReady = $false
    $backendReady = $false

    Write-Host "Waiting for real services..."
    while ((Get-Date) -lt $deadline) {
        if (-not $backendReady) {
            $backendReady = Test-HttpReady "http://localhost:8080/actuator/health"
        }
        if (-not $frontendReady) {
            $frontendReady = Test-HttpReady "http://localhost:5173"
        }
        if ($backendReady -and $frontendReady) {
            return
        }
        Start-Sleep -Seconds 5
    }

    throw "Services did not become ready in time. Run logs.bat to inspect Docker logs."
}

if (-not (Test-Command "docker")) {
    throw "Docker was not found. Real mode requires Docker Engine / Docker Desktop."
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker is not running. Start Docker first, then run start.bat again."
}

docker compose version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose was not found. Update Docker Desktop or install Docker Compose."
}

Write-Host "Starting real mode: MySQL + Milvus + MinIO + backend + frontend."
docker compose up -d --build
if ($LASTEXITCODE -ne 0) {
    throw "docker compose up failed."
}

Wait-Services

Write-Host ""
Write-Host "Application is ready:"
Write-Host "  Frontend: http://localhost:5173"
Write-Host "  Backend:  http://localhost:8080"
$lanIp = Get-LanIp
if ($lanIp) {
    Write-Host "  LAN Frontend: http://${lanIp}:5173"
    Write-Host "  LAN Backend:  http://${lanIp}:8080"
}
Write-Host ""

Start-Process "http://localhost:5173"
