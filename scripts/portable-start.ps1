param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$RuntimeDir = Join-Path $Root ".runtime"
$AppDir = Join-Path $Root "app"
$DataDir = Join-Path $Root "data"
$LogDir = Join-Path $Root "logs"
$BackendJar = Join-Path $AppDir "backend.jar"
$FrontendDir = Join-Path $AppDir "frontend"
$PidFile = Join-Path $RuntimeDir "backend.pid"

New-Item -ItemType Directory -Force -Path $RuntimeDir, $AppDir, $DataDir, $LogDir | Out-Null

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message"
}

function Test-Command([string]$Command) {
    $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
}

function Get-JavaMajor([string]$JavaExe) {
    try {
        $output = & $JavaExe -version 2>&1
        $line = ($output | Select-Object -First 1)
        if ($line -match '"(?<version>[0-9]+)(\.|")') {
            return [int]$Matches.version
        }
    } catch {
        return 0
    }
    return 0
}

function Find-JavaHome([string]$BaseDir) {
    if (Test-Path $BaseDir) {
        $java = Get-ChildItem -Path $BaseDir -Filter java.exe -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "\\bin\\java.exe$" } |
            Select-Object -First 1
        if ($java) {
            return (Split-Path (Split-Path $java.FullName -Parent) -Parent)
        }
    }
    return $null
}

function Ensure-Java {
    if (Test-Command "java") {
        $major = Get-JavaMajor "java"
        if ($major -ge 21) {
            Write-Host "Using system Java $major."
            return (Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent)
        }
    }

    $javaRoot = Join-Path $RuntimeDir "java"
    $javaHome = Find-JavaHome $javaRoot
    if ($javaHome) {
        return $javaHome
    }

    Write-Step "Downloading portable JDK 21"
    $zip = Join-Path $RuntimeDir "jdk21.zip"
    $url = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    if (Test-Path $javaRoot) {
        Remove-Item -LiteralPath $javaRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $javaRoot | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $javaRoot -Force
    Remove-Item -LiteralPath $zip -Force

    $javaHome = Find-JavaHome $javaRoot
    if (-not $javaHome) {
        throw "Portable JDK download completed, but java.exe was not found."
    }
    return $javaHome
}

function Find-NodeHome([string]$BaseDir) {
    if (Test-Path $BaseDir) {
        $node = Get-ChildItem -Path $BaseDir -Filter node.exe -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($node) {
            return (Split-Path $node.FullName -Parent)
        }
    }
    return $null
}

function Ensure-Node {
    if (Test-Command "node") {
        $version = & node --version
        if ($version -match '^v20\.') {
            Write-Host "Using system Node $version."
            return (Split-Path (Get-Command node).Source -Parent)
        }
    }

    $nodeRoot = Join-Path $RuntimeDir "node"
    $nodeHome = Find-NodeHome $nodeRoot
    if ($nodeHome) {
        return $nodeHome
    }

    Write-Step "Downloading portable Node.js 20"
    $index = Invoke-RestMethod -Uri "https://nodejs.org/dist/index.json"
    $version = ($index | Where-Object { $_.version -match '^v20\.' } | Select-Object -First 1).version
    if (-not $version) {
        throw "Could not resolve latest Node.js 20 version from nodejs.org."
    }

    $zip = Join-Path $RuntimeDir "node20.zip"
    $url = "https://nodejs.org/dist/$version/node-$version-win-x64.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    if (Test-Path $nodeRoot) {
        Remove-Item -LiteralPath $nodeRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $nodeRoot | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $nodeRoot -Force
    Remove-Item -LiteralPath $zip -Force

    $nodeHome = Find-NodeHome $nodeRoot
    if (-not $nodeHome) {
        throw "Portable Node.js download completed, but node.exe was not found."
    }
    return $nodeHome
}

function Load-DotEnv {
    $envPath = Join-Path $Root ".env"
    if (-not (Test-Path $envPath)) {
        return
    }
    Get-Content -LiteralPath $envPath | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            return
        }
        $parts = $line.Split("=", 2)
        $name = $parts[0].Trim()
        $value = $parts[1].Trim().Trim('"')
        if ($name) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Build-FrontendIfMissing {
    $index = Join-Path $FrontendDir "index.html"
    if (Test-Path $index) {
        return
    }
    $sourceDir = Join-Path $Root "frontend"
    if (-not (Test-Path (Join-Path $sourceDir "package.json"))) {
        throw "Frontend artifact is missing and frontend source was not found."
    }

    $nodeHome = Ensure-Node
    $env:PATH = "$nodeHome;$env:PATH"

    $buildDir = Join-Path $RuntimeDir "frontend-build"
    if (Test-Path $buildDir) {
        Remove-Item -LiteralPath $buildDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $sourceDir "package.json") -Destination $buildDir -Force
    Copy-Item -LiteralPath (Join-Path $sourceDir "package-lock.json") -Destination $buildDir -Force
    Copy-Item -LiteralPath (Join-Path $sourceDir "index.html") -Destination $buildDir -Force
    Copy-Item -LiteralPath (Join-Path $sourceDir "tsconfig.json") -Destination $buildDir -Force
    Copy-Item -LiteralPath (Join-Path $sourceDir "tsconfig.node.json") -Destination $buildDir -Force
    Copy-Item -LiteralPath (Join-Path $sourceDir "vite.config.ts") -Destination $buildDir -Force
    Copy-Item -LiteralPath (Join-Path $sourceDir "src") -Destination $buildDir -Recurse -Force

    Write-Step "Building frontend"
    Push-Location $buildDir
    try {
        & (Join-Path $nodeHome "npm.cmd") ci
        if (-not (Test-Path (Join-Path $buildDir "node_modules\.bin\vue-tsc.cmd"))) {
            throw "npm ci failed."
        }
        & (Join-Path $nodeHome "npm.cmd") run build
        if (-not (Test-Path (Join-Path $buildDir "dist\index.html"))) {
            throw "npm run build failed."
        }
    } finally {
        Pop-Location
    }

    if (Test-Path $FrontendDir) {
        Remove-Item -LiteralPath $FrontendDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $FrontendDir | Out-Null
    Copy-Item -Path (Join-Path $buildDir "dist\*") -Destination $FrontendDir -Recurse -Force
}

function Build-BackendIfMissing {
    if (Test-Path $BackendJar) {
        return
    }
    $mvnw = Join-Path $Root "backend\mvnw.cmd"
    if (-not (Test-Path $mvnw)) {
        throw "Backend artifact is missing and Maven Wrapper was not found."
    }

    Write-Step "Building backend"
    Push-Location $Root
    try {
        & $mvnw -f (Join-Path $Root "pom.xml") -q -pl backend -am -DskipTests package
    } finally {
        Pop-Location
    }

    $builtJar = Join-Path $Root "backend\target\backend-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $builtJar)) {
        throw "Maven package failed or target jar was not found."
    }
    Copy-Item -LiteralPath $builtJar -Destination $BackendJar -Force
}

function Test-HttpReady([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

function Wait-Backend {
    $deadline = (Get-Date).AddSeconds(120)
    $health = "http://localhost:$Port/actuator/health"
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpReady $health) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Backend did not become ready. Check logs/backend.log and logs/backend-error.log."
}

Load-DotEnv

$javaHome = Ensure-Java
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

Build-FrontendIfMissing
Build-BackendIfMissing

$healthUrl = "http://localhost:$Port/actuator/health"
if (Test-HttpReady $healthUrl) {
    Write-Host "Application is already running."
    Start-Process "http://localhost:$Port"
    exit 0
}

Write-Step "Starting backend in portable mode"
$frontendLocation = "file:/" + (($FrontendDir -replace "\\", "/").TrimEnd("/") + "/")
$env:SPRING_PROFILES_ACTIVE = "portable"
$env:SERVER_PORT = "$Port"
$env:APP_UPLOAD_DIR = (Join-Path $DataDir "uploads")
$env:VECTOR_PROVIDER = "local"
$env:AGENT_MOCK = "false"

$stdout = Join-Path $LogDir "backend.log"
$stderr = Join-Path $LogDir "backend-error.log"
if (Test-Path $stdout) { Remove-Item -LiteralPath $stdout -Force }
if (Test-Path $stderr) { Remove-Item -LiteralPath $stderr -Force }

$args = @(
    "-Dfile.encoding=UTF-8",
    "-Dspring.web.resources.static-locations=$frontendLocation",
    "-jar",
    $BackendJar
)

$process = Start-Process -FilePath (Join-Path $javaHome "bin\java.exe") `
    -ArgumentList $args `
    -WorkingDirectory $Root `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ASCII
Wait-Backend

Write-Host ""
Write-Host "Application is ready:"
Write-Host "  http://localhost:$Port"
Write-Host ""
Start-Process "http://localhost:$Port"
