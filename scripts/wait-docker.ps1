$ErrorActionPreference = "SilentlyContinue"

$deadline = (Get-Date).AddMinutes(3)
Write-Host "Waiting for Docker Desktop..."

while ((Get-Date) -lt $deadline) {
    docker info *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Docker is ready."
        exit 0
    }
    Start-Sleep -Seconds 3
}

Write-Host "Docker is still not ready. Open Docker Desktop manually and run start.bat again."
exit 1
