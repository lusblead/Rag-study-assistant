$ErrorActionPreference = "SilentlyContinue"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PidFile = Join-Path $Root ".runtime\backend.pid"

if (-not (Test-Path $PidFile)) {
    Write-Host "No portable backend pid file found."
    exit 0
}

$pidValue = (Get-Content -LiteralPath $PidFile | Select-Object -First 1).Trim()
if (-not $pidValue) {
    Remove-Item -LiteralPath $PidFile -Force
    Write-Host "No portable backend pid found."
    exit 0
}

$process = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
if ($process) {
    Stop-Process -Id $process.Id -Force
    Write-Host "Stopped portable backend process $pidValue."
} else {
    Write-Host "Portable backend process $pidValue is not running."
}

Remove-Item -LiteralPath $PidFile -Force
