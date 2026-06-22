$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

Write-Host "Stopping real mode Docker services..."
docker compose down
Write-Host "Stopped. Docker volumes are preserved."
