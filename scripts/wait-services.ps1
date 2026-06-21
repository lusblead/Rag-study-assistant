param(
    [string]$FrontendUrl = "http://localhost:5173",
    [string]$BackendUrl = "http://localhost:8080/actuator/health",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "SilentlyContinue"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$frontendReady = $false
$backendReady = $false

function Test-HttpReady([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

Write-Host "Waiting for backend and frontend..."

while ((Get-Date) -lt $deadline) {
    if (-not $backendReady) {
        $backendReady = Test-HttpReady $BackendUrl
    }
    if (-not $frontendReady) {
        $frontendReady = Test-HttpReady $FrontendUrl
    }

    if ($backendReady -and $frontendReady) {
        Write-Host "Services are ready."
        exit 0
    }

    Start-Sleep -Seconds 5
}

Write-Host "Readiness check timed out."
Write-Host "Backend ready:  $backendReady"
Write-Host "Frontend ready: $frontendReady"
exit 1
