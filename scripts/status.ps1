$ErrorActionPreference = "SilentlyContinue"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

docker compose ps

Write-Host ""
try {
    $health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 5
    Write-Host "Backend health: $($health.status)"
} catch {
    Write-Host "Backend health: not ready"
}

try {
    $response = Invoke-WebRequest -Uri "http://localhost:5173" -UseBasicParsing -TimeoutSec 5
    Write-Host "Frontend HTTP: $($response.StatusCode)"
} catch {
    Write-Host "Frontend HTTP: not ready"
}
