param(
    [string]$Name = "Rag-study-assistant-one-click"
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$ReleaseDir = Join-Path $Root "release"
$StageDir = Join-Path $ReleaseDir $Name
$ZipPath = Join-Path $ReleaseDir "$Name.zip"
$AppDir = Join-Path $StageDir "app"
$StageFrontendDir = Join-Path $AppDir "frontend"
$BackendJar = Join-Path $Root "backend\target\backend-0.0.1-SNAPSHOT.jar"
$FrontendDist = Join-Path $Root "frontend\dist"

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
    ".runtime",
    ".git",
    ".idea",
    ".vscode",
    ".cache",
    "app",
    "data",
    "logs",
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

$FrontendSource = Join-Path $Root "frontend"
$FrontendBuild = Join-Path $ReleaseDir ".frontend-build"
if (Test-Path $FrontendBuild) {
    Remove-Item -LiteralPath $FrontendBuild -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $FrontendBuild | Out-Null
Copy-Item -LiteralPath (Join-Path $FrontendSource "package.json") -Destination $FrontendBuild -Force
Copy-Item -LiteralPath (Join-Path $FrontendSource "package-lock.json") -Destination $FrontendBuild -Force
Copy-Item -LiteralPath (Join-Path $FrontendSource "index.html") -Destination $FrontendBuild -Force
Copy-Item -LiteralPath (Join-Path $FrontendSource "tsconfig.json") -Destination $FrontendBuild -Force
Copy-Item -LiteralPath (Join-Path $FrontendSource "tsconfig.node.json") -Destination $FrontendBuild -Force
Copy-Item -LiteralPath (Join-Path $FrontendSource "vite.config.ts") -Destination $FrontendBuild -Force
Copy-Item -LiteralPath (Join-Path $FrontendSource "src") -Destination $FrontendBuild -Recurse -Force

Write-Host "Building frontend..."
Push-Location $FrontendBuild
try {
    npm ci
    if (-not (Test-Path (Join-Path $FrontendBuild "node_modules\.bin\vue-tsc.cmd"))) {
        throw "npm ci failed"
    }
    npm run build
    if (-not (Test-Path (Join-Path $FrontendBuild "dist\index.html"))) {
        throw "npm run build failed"
    }
} finally {
    Pop-Location
}

Write-Host "Building backend..."
$mvnw = Join-Path $Root "backend\mvnw.cmd"
& $mvnw -f (Join-Path $Root "pom.xml") -q -pl backend -am -DskipTests package
if (-not (Test-Path $BackendJar)) { throw "Maven package failed or backend jar not found: $BackendJar" }
if (-not (Test-Path (Join-Path $FrontendBuild "dist\index.html"))) { throw "Frontend dist not found: $FrontendBuild\dist" }

robocopy $Root $StageDir /E /XD $excludeDirs /XF $excludeFiles /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) {
    throw "robocopy failed with exit code $LASTEXITCODE"
}

New-Item -ItemType Directory -Force -Path $AppDir, $StageFrontendDir | Out-Null
Copy-Item -LiteralPath $BackendJar -Destination (Join-Path $AppDir "backend.jar") -Force
Copy-Item -Path (Join-Path $FrontendBuild "dist\*") -Destination $StageFrontendDir -Recurse -Force

Compress-Archive -LiteralPath $StageDir -DestinationPath $ZipPath -Force

Write-Host "Release package created:"
Write-Host $ZipPath
