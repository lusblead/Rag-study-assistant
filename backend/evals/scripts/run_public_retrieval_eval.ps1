param(
    [string]$DatasetDir = "backend/evals/datasets/t2-retrieval-public-v1",
    [string]$OutputDir = "backend/evals/runs",
    [string]$EmbeddingCache = ".cache/rag-public-eval/corpus-embeddings.jsonl",
    [ValidateSet("live", "replay")]
    [string]$QueryMode = "replay"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$composeFile = Join-Path $projectRoot "backend\evals\docker-compose.public-eval.yml"
$mysqlWasRunning = [bool](docker ps --filter "name=^/rag-study-mysql$" --format "{{.Names}}")

Push-Location $projectRoot
try {
    # 用户可以显式传入环境变量；若未传入，则复用项目设置页已保存的配置。
    # 密钥只存在于当前 PowerShell 进程及 Maven 子进程，不写入参数、报告或仓库文件。
    if ([string]::IsNullOrWhiteSpace($env:RAG_EVAL_EMBEDDING_API_KEY)) {
        if (-not $mysqlWasRunning) {
            docker start rag-study-mysql | Out-Null
        }
        $deadline = (Get-Date).AddSeconds(45)
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

    docker compose -p rag-public-eval -f $composeFile up -d
    $deadline = (Get-Date).AddSeconds(90)
    do {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:39591/healthz" -TimeoutSec 2
            if ($health -eq "OK") { break }
        } catch { }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    if ($health -ne "OK") {
        docker compose -p rag-public-eval -f $composeFile logs --tail 100 eval-milvus
        throw "临时 Milvus 未在 90 秒内就绪"
    }

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    $timestamp = Get-Date -Format "yyyy-MM-ddTHH-mm-sszzz"
    $timestamp = $timestamp.Replace(":", "")
    $output = Join-Path $OutputDir "$timestamp-$QueryMode-t2-public-bge-m3-milvus.json"
    $revision = (git rev-parse HEAD).Trim()
    $diff = git diff --binary -- . ':!.cache' ':!backend/evals/runs'
    # `git diff` 不包含未跟踪文件；评测代码和新数据集在首次运行时往往尚未纳入 Git。
    # 将所有未跟踪且未忽略文件的路径与内容哈希加入指纹，同时排除每次都会新增的报告。
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

    $env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $mavenArgs = @(
        "-pl", "backend",
        "-Dtest=PublicRetrievalEvalTest",
        "-Drag.public.eval=true",
        "-Drag.eval.datasetDir=$DatasetDir",
        "-Drag.eval.output=$output",
        "-Drag.eval.embeddingCache=$EmbeddingCache",
        "-Drag.eval.queryMode=$QueryMode",
        "-Drag.eval.milvusHost=127.0.0.1",
        "-Drag.eval.milvusPort=39530",
        "-Drag.eval.milvusCollection=rag_public_t2_eval_v1",
        "-Drag.eval.milvusIndexType=FLAT",
        "-Drag.eval.codeRevision=$revision",
        "-Drag.eval.worktreeFingerprint=$fingerprint",
        "test"
    )
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "公开检索评测失败，Maven 退出码 $LASTEXITCODE"
    }
    Write-Output "公开检索评测报告: $output"
} finally {
    # Docker Compose 会把正常的停止进度写到 stderr。PowerShell 5 在 Stop 模式下会把它
    # 包装成 NativeCommandError，因此清理阶段临时降级错误流；随后仍用退出码判断清理结果。
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    & docker compose -p rag-public-eval -f $composeFile down --remove-orphans 2>$null | Out-Null
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
