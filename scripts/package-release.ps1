param(
    [string]$Name = "Rag-study-assistant-one-click"
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$ReleaseDir = Join-Path $Root "release"
$StageDir = Join-Path $ReleaseDir $Name
$ZipPath = Join-Path $ReleaseDir "$Name.zip"

New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
$ReleaseRoot = [System.IO.Path]::GetFullPath((Resolve-Path $ReleaseDir))
$StageRoot = [System.IO.Path]::GetFullPath($StageDir)

if (-not $StageRoot.StartsWith($ReleaseRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean a staging directory outside release/: $StageRoot"
}

if (Test-Path $StageDir) {
    Remove-Item -LiteralPath $StageDir -Recurse -Force
}
if (Test-Path $ZipPath) {
    Remove-Item -LiteralPath $ZipPath -Force
}

$excludeDirs = @(
    ".agents",
    ".claude",
    ".git",
    ".idea",
    ".vscode",
    ".cache",
    "release",
    "target",
    "node_modules",
    "dist",
    "uploads",
    "mysql_data",
    "milvus_data",
    "minio_data",
    "etcd_data",
    "uploads_data"
)

$excludeFiles = @(
    ".env",
    "CLAUDE.md",
    "*.log",
    "*.tsbuildinfo"
)

robocopy $Root $StageDir /E /XD $excludeDirs /XF $excludeFiles /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) {
    throw "robocopy failed with exit code $LASTEXITCODE"
}

Compress-Archive -LiteralPath $StageDir -DestinationPath $ZipPath -Force

Write-Host "Release package created:"
Write-Host $ZipPath
