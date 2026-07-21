$ErrorActionPreference = "SilentlyContinue"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PidFile = Join-Path $Root ".runtime\backend.pid"
$health = "http://localhost:8080/actuator/health"

if (Test-Path $PidFile) {
    $pidValue = (Get-Content -LiteralPath $PidFile | Select-Object -First 1).Trim()
    $process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "Backend process: running (pid=$pidValue)"
    } else {
        Write-Host "Backend process: not running (stale pid=$pidValue)"
    }
} else {
    Write-Host "Backend process: no pid file"
}

try {
    $response = Invoke-RestMethod -Uri $health -TimeoutSec 3
    Write-Host "Health endpoint: $($response.status)"
    Write-Host "URL: http://localhost:8080"
} catch {
    Write-Host "Health endpoint: not ready"
}
