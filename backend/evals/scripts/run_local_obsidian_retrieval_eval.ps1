param(
    [string]$DatasetDir = "backend/evals/datasets/local-obsidian-v3-reviewed",
    [string]$CaseFile = "standard_reviewed_100_retrieval.jsonl",
    [string]$OutputDir = "backend/evals/runs",
    [string]$EmbeddingCache = ".cache/local-obsidian-eval/corpus-embeddings.jsonl",
    [ValidateSet("live", "replay")]
    [string]$QueryMode = "replay"
)

# 本地 Obsidian v3 reviewed 检索评测 Harness。
# 与公共 T2 Harness 复用同一套硅基流动 BGE-M3、临时 Milvus 2.4.11、FLAT 精确索引
# 和 flush+count 可见性屏障；唯一区别是 case 文件来自已人工复核的本地业务数据集。
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$composeFile = Join-Path $projectRoot "backend\evals\docker-compose.public-eval.yml"
$mysqlWasRunning = [bool](docker ps --filter "name=^/rag-study-mysql$" --format "{{.Names}}")
$resolvedDatasetDir = Join-Path $projectRoot $DatasetDir
if (-not (Test-Path -LiteralPath (Join-Path $resolvedDatasetDir "corpus.jsonl"))) {
    throw "本地 Obsidian 数据集不存在: $resolvedDatasetDir。请通过 -DatasetDir 挂载外置数据集。"
}
if (-not (Test-Path -LiteralPath (Join-Path $resolvedDatasetDir "manifest.json"))) {
    throw "本地 Obsidian 数据集缺少 manifest.json: $resolvedDatasetDir。请按 datasets/README.md 生成并校验。"
}

Push-Location $projectRoot
try {
    # API Key 只存在于当前 PowerShell 进程及 Maven 子进程，不写入参数、报告或仓库文件。
    if ([string]::IsNullOrWhiteSpace($env:RAG_EVAL_EMBEDDING_API_KEY)) {
        if (-not $mysqlWasRunning) {
            docker start rag-study-mysql | Out-Null
        }
        $deadline = (Get-Date).AddSeconds(45)
        $health = ""
        do {
            $health = docker inspect --format "{{.State.Health.Status}}" rag-study-mysql 2>$null
            if ($health -eq "healthy") { break }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        if ($health -ne "healthy") {
            throw "项目 MySQL 未达到 healthy，无法读取已保存的 Embedding 配置"
        }

        $sql = "SELECT embedding_base_url, embedding_model, embedding_api_key FROM agent_model_settings ORDER BY id DESC LIMIT 1"
        $mysqlArgs = @(
            "exec", "-e", "MYSQL_PWD=root", "rag-study-mysql", "mysql", "-uroot",
            "-D", "rag_study_assistant", "--batch", "--raw",
            "--skip-column-names", "-e", $sql
        )
        $row = & docker @mysqlArgs 2>$null
        $parts = $row -split "`t", 3
        if ($parts.Count -ne 3 -or [string]::IsNullOrWhiteSpace($parts[2])) {
            throw "项目设置页没有可用的 Embedding API Key"
        }
        $env:RAG_EVAL_EMBEDDING_BASE_URL = $parts[0]
        $env:RAG_EVAL_EMBEDDING_MODEL = $parts[1]
        $env:RAG_EVAL_EMBEDDING_API_KEY = $parts[2]
    }

    if ([string]::IsNullOrWhiteSpace($env:RAG_EVAL_EMBEDDING_BASE_URL)) {
        $env:RAG_EVAL_EMBEDDING_BASE_URL = "https://api.siliconflow.cn/v1"
    }
    if ([string]::IsNullOrWhiteSpace($env:RAG_EVAL_EMBEDDING_MODEL)) {
        $env:RAG_EVAL_EMBEDDING_MODEL = "BAAI/bge-m3"
    }

    docker compose -p rag-local-eval -f $composeFile up -d
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
        docker compose -p rag-local-eval -f $composeFile logs --tail 100 eval-milvus
        throw "临时 Milvus 未在 90 秒内就绪"
    }

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $timestamp = Get-Date -Format "yyyy-MM-ddTHH-mm-sszzz"
    $timestamp = $timestamp.Replace(":", "")
    $output = Join-Path $OutputDir "$timestamp-$QueryMode-local-obsidian-v3-bge-m3-milvus.json"
    $revision = (git rev-parse HEAD).Trim()
    $diff = git diff --binary -- . ':!.cache' ':!backend/evals/runs'
    # 未跟踪评测输入也纳入指纹，排除每次运行新增的报告目录。
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

    # 优先使用当前会话 JAVA_HOME；缺失时给出清晰错误，不硬编码本机路径。
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "未设置 JAVA_HOME。请先设置 JAVA_HOME 指向 JDK 21+ 安装目录后再运行。"
    }
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $mavenArgs = @(
        "-pl", "backend",
        "-Dtest=LocalObsidianRetrievalEvalTest",
        "-Drag.local.obsidian.eval=true",
        "-Drag.eval.datasetDir=$DatasetDir",
        "-Drag.local.obsidian.caseFile=$CaseFile",
        "-Drag.eval.output=$output",
        "-Drag.eval.embeddingCache=$EmbeddingCache",
        "-Drag.eval.queryMode=$QueryMode",
        "-Drag.eval.milvusHost=127.0.0.1",
        "-Drag.eval.milvusPort=39530",
        "-Drag.eval.milvusCollection=rag_obsidian_v3_eval_v1",
        "-Drag.eval.milvusIndexType=FLAT",
        "-Drag.eval.codeRevision=$revision",
        "-Drag.eval.worktreeFingerprint=$fingerprint",
        "test"
    )
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "本地 Obsidian 检索评测失败，Maven 退出码 $LASTEXITCODE"
    }
    Write-Output "本地 Obsidian 检索评测报告: $output"
} finally {
    # Docker Compose 正常停止进度会写到 stderr；仅在清理命令周围临时处理，随后检查退出码。
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    & docker compose -p rag-local-eval -f $composeFile down --remove-orphans 2>$null | Out-Null
    $composeDownExitCode = $LASTEXITCODE
    $env:RAG_EVAL_EMBEDDING_API_KEY = $null
    if (-not $mysqlWasRunning) {
        & docker stop rag-study-mysql 2>$null | Out-Null
    }
    $ErrorActionPreference = $previousErrorAction
    Pop-Location
    if ($composeDownExitCode -ne 0) {
        Write-Warning "临时评测容器清理失败，docker compose 退出码 $composeDownExitCode"
    }
}
