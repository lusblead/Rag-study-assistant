$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$LogDir = Join-Path $Root "logs"
$stdout = Join-Path $LogDir "backend.log"
$stderr = Join-Path $LogDir "backend-error.log"

if (-not (Test-Path $stdout) -and -not (Test-Path $stderr)) {
    Write-Host "No logs found yet."
    exit 0
}

Write-Host "Showing backend logs. Press Ctrl+C to stop."
if (Test-Path $stderr) {
    Write-Host ""
    Write-Host "---- backend-error.log ----"
    Get-Content -LiteralPath $stderr -Tail 80
}
if (Test-Path $stdout) {
    Write-Host ""
    Write-Host "---- backend.log ----"
    Get-Content -LiteralPath $stdout -Wait -Tail 200
}
