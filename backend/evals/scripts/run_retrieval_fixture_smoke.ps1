param(
    [string]$FixtureDir = "backend/src/test/resources/eval-fixtures/retrieval-smoke",
    [string]$OutputDir = "backend/evals/runs"
)

# 无密钥公开合成 Fixture 的检索 Smoke。
# 不读取 MySQL 模型配置、不要求 API Key、不调用外部 Embedding API；
# 仅验证评测代码、临时 Milvus FLAT、指标与报告链路可运行。
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$composeFile = Join-Path $projectRoot "backend\evals\docker-compose.public-eval.yml"

Push-Location $projectRoot
try {
    docker compose -p rag-fixture-smoke -f $composeFile up -d
    $deadline = (Get-Date).AddSeconds(90)
    $health = ""
    do {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:39591/healthz" -TimeoutSec 2
            if ($health -eq "OK") { break }
        } catch { }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    if ($health -ne "OK") {
        docker compose -p rag-fixture-smoke -f $composeFile logs --tail 100 eval-milvus
        throw "临时 Milvus 未在 90 秒内就绪"
    }

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $timestamp = Get-Date -Format "yyyy-MM-ddTHH-mm-sszzz"
    $timestamp = $timestamp.Replace(":", "")
    $output = Join-Path $OutputDir "$timestamp-fixture-smoke.json"
    # git 缺失时（如干净导出目录）Smoke 仍应可运行；revision/fingerprint 降级为 UNRECORDED。
    $revision = "UNRECORDED"
    $fingerprint = "UNRECORDED"
    if (Test-Path -LiteralPath (Join-Path $projectRoot ".git")) {
        $revision = (git rev-parse HEAD).Trim()
        $diff = git diff --binary -- . ':!.cache' ':!backend/evals/runs'
        $untrackedHashes = git ls-files --others --exclude-standard -- . | Where-Object {
            $_ -notmatch '^backend/evals/runs/'
        } | ForEach-Object {
            $path = $_
            $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            "$path`t$hash"
        }
        $fingerprintMaterial = ($diff -join "`n") + "`nUNTRACKED`n" + ($untrackedHashes -join "`n")
        $fingerprintBytes = [Text.Encoding]::UTF8.GetBytes($fingerprintMaterial)
        $sha256 = [Security.Cryptography.SHA256]::Create()
        $fingerprint = -join ($sha256.ComputeHash($fingerprintBytes) | ForEach-Object {
            $_.ToString("x2")
        })
        $sha256.Dispose()
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "未设置 JAVA_HOME。请先设置 JAVA_HOME 指向 JDK 21+ 安装目录后再运行。"
    }
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $mavenArgs = @(
        "-pl", "backend",
        "-Dtest=RetrievalFixtureSmokeTest",
        "-Drag.fixture.smoke=true",
        "-Drag.fixture.dir=$FixtureDir",
        "-Drag.eval.output=$output",
        "-Drag.eval.milvusHost=127.0.0.1",
        "-Drag.eval.milvusPort=39530",
        "-Drag.eval.milvusCollection=rag_fixture_smoke_v1",
        "-Drag.eval.milvusIndexType=FLAT",
        "-Drag.eval.codeRevision=$revision",
        "-Drag.eval.worktreeFingerprint=$fingerprint",
        "test"
    )
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Fixture Smoke 失败，Maven 退出码 $LASTEXITCODE"
    }

    # 验证关键字段存在，报告不含 API Key 敏感字段。
    $report = Get-Content -Raw -Encoding UTF8 -LiteralPath $output | ConvertFrom-Json
    if ($null -eq $report.overall -or $null -eq $report.cases) {
        throw "Fixture Smoke 报告缺少 overall/cases 字段: $output"
    }
    if ($report.cases.Count -ne $report.dataset.expectedCaseCount) {
        throw "Fixture Smoke case 数量不匹配: expected=$($report.dataset.expectedCaseCount), actual=$($report.cases.Count)"
    }
    $serialized = Get-Content -Raw -Encoding UTF8 -LiteralPath $output
    foreach ($sensitive in @('api_key', 'apikey', 'authorization', 'bearer ')) {
        if ($serialized.ToLower().Contains($sensitive)) {
            throw "Fixture Smoke 报告疑似包含敏感字段: $sensitive"
        }
    }

    Write-Output "Fixture Smoke 报告: $output"
    Write-Output ("overall: " + ($report.overall | ConvertTo-Json -Compress))
} finally {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    & docker compose -p rag-fixture-smoke -f $composeFile down --remove-orphans 2>$null | Out-Null
    $composeDownExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    Pop-Location
    if ($composeDownExitCode -ne 0) {
        Write-Warning "临时评测容器清理失败，docker compose 退出码 $composeDownExitCode"
    }
}
