param(
    [string]$DatasetDir = "backend/evals/datasets/local-obsidian-v3-reviewed",
    [string]$CaseFile = "standard_reviewed_100_retrieval_dev.jsonl",
    [int]$CandidateK = 20,
    [int]$FinalK = 5,
    [ValidateSet("live", "replay")]
    [string]$QueryMode = "replay",
    [ValidateSet("true", "false")]
    [string]$RemoteFailOpen = "true",
    [ValidateSet("true", "false")]
    [string]$RemoteThresholdEnabled = "false",
    [double]$RemoteThresholdMinScore = 0.0,
    [ValidateSet("true", "false")]
    [string]$LocalThresholdEnabled = "false",
    [double]$LocalThresholdMinScore = 0.0,
    [ValidateSet("true", "false")]
    [string]$CompositeEnabled = "false",
    [string]$CompositeVersion = "normalized-min-max-v1",
    [double]$CompositeBaseWeight = 0.5,
    [double]$CompositeRerankWeight = 0.5,
    [string]$OutputDir = "backend/evals/reports"
)

# Step 3.1 Dense-only 候选上的受控 Dev45 入口；不是 Hybrid/MySQL Lexical/RRF 验收。
# 所有 gate 在 Maven、Docker、Milvus、Provider 之前执行。
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$expectedDev45 = "standard_reviewed_100_retrieval_dev.jsonl"

function Write-NotRunManifest([string[]]$Blockers) {
    $payload = [ordered]@{
        schema = "reviewed-dev45-rerank-ablation/v1"
        runStatus = "NOT_RUN"
        split = "dev"
        evidenceBoundary = "Frozen Dense-only candidates; not Hybrid/MySQL Lexical/RRF acceptance"
        plannedArms = @("none", "local", "remote")
        metrics = $null
        blockers = $Blockers
    } | ConvertTo-Json -Depth 4
    $stream = [System.IO.File]::Open(
        $notRunOutput,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None)
    try {
        $writer = [System.IO.StreamWriter]::new(
            $stream, [System.Text.UTF8Encoding]::new($false))
        try { $writer.WriteLine($payload) } finally { $writer.Dispose() }
    } finally {
        $stream.Dispose()
    }
    Write-Output "NOT_RUN manifest: $notRunOutput"
}

if ($CaseFile -cne $expectedDev45) {
    throw "Step 3.1 only permits explicit reviewed Dev45 case file: $expectedDev45"
}
if ($CandidateK -le 0 -or $FinalK -le 0 -or $CandidateK -lt $FinalK) {
    throw "CandidateK must be >= FinalK > 0"
}
foreach ($value in @(
        $RemoteThresholdMinScore,
        $LocalThresholdMinScore,
        $CompositeBaseWeight,
        $CompositeRerankWeight)) {
    if ([double]::IsNaN($value) -or [double]::IsInfinity($value)) {
        throw "Thresholds and composite weights must be finite."
    }
}
if ($CompositeBaseWeight -lt 0 -or $CompositeRerankWeight -lt 0 -or
        ($CompositeBaseWeight -eq 0 -and $CompositeRerankWeight -eq 0)) {
    throw "Composite weights must be non-negative and at least one must be positive."
}
if ([string]::IsNullOrWhiteSpace($CompositeVersion)) {
    throw "CompositeVersion must not be blank."
}

$resolvedDatasetDir = if ([IO.Path]::IsPathRooted($DatasetDir)) {
    [IO.Path]::GetFullPath($DatasetDir)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $DatasetDir))
}
if (-not (Test-Path -LiteralPath $resolvedDatasetDir -PathType Container)) {
    throw "Reviewed dataset directory is unavailable."
}
$resolvedOutputDir = if ([IO.Path]::IsPathRooted($OutputDir)) {
    [IO.Path]::GetFullPath($OutputDir)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir))
}
if (-not (Test-Path -LiteralPath $resolvedOutputDir -PathType Container)) {
    New-Item -ItemType Directory -Path $resolvedOutputDir | Out-Null
}
$stamp = (Get-Date).ToString("yyyy-MM-ddTHH-mm-ssfff")
$qualityOutput = Join-Path $resolvedOutputDir "$stamp-reviewed-dev45-data-quality.json"
$readinessOutput = Join-Path $resolvedOutputDir "$stamp-reviewed-dev45-readiness.json"
$notRunOutput = Join-Path $resolvedOutputDir "$stamp-reviewed-dev45-rerank-ablation-not-run.json"
$resultOutput = Join-Path $resolvedOutputDir "$stamp-reviewed-dev45-rerank-ablation.json"

Push-Location $projectRoot
$composeStarted = $false
try {
    # aggregate-only、exclusive-create；退出码非 0 时不允许进行任何真实评测调用。
    & python -X utf8 "backend/evals/scripts/check_dataset_quality.py" `
        --profile reviewed-local-v1 `
        --dataset-dir $resolvedDatasetDir `
        --output $qualityOutput
    if ($LASTEXITCODE -ne 0) {
        Write-NotRunManifest @("DATA_GATE_NOT_ACCEPTABLE")
        exit 2
    }

    & python -X utf8 "backend/evals/scripts/check_b0_readiness.py" `
        --repo-root $projectRoot `
        --dataset-dir $DatasetDir `
        --case-file $CaseFile `
        --output $readinessOutput
    if ($LASTEXITCODE -ne 0) {
        Write-NotRunManifest @("B0_READINESS_NOT_ACCEPTABLE")
        exit 2
    }

    foreach ($name in @(
            "RAG_EVAL_EMBEDDING_BASE_URL",
            "RAG_EVAL_EMBEDDING_MODEL",
            "RAG_EVAL_EMBEDDING_API_KEY",
            "RAG_EVAL_RERANK_BASE_URL",
            "RAG_EVAL_RERANK_MODEL",
            "RAG_EVAL_RERANK_API_KEY")) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            Write-NotRunManifest @("REQUIRED_ENVIRONMENT_MISSING")
            exit 2
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        Write-NotRunManifest @("JAVA_HOME_MISSING")
        exit 2
    }

    $composeFile = Join-Path $projectRoot "backend/evals/docker-compose.public-eval.yml"
    docker compose -p rag-dev45-rerank-eval -f $composeFile up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Temporary Milvus startup failed; Docker exit code $LASTEXITCODE."
    }
    $composeStarted = $true
    $deadline = (Get-Date).AddSeconds(90)
    do {
        try {
            if ((Invoke-RestMethod -Uri "http://127.0.0.1:39591/healthz" -TimeoutSec 2) -eq "OK") {
                break
            }
        } catch { }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    if ((Get-Date) -ge $deadline) {
        throw "Temporary Milvus did not become healthy within 90 seconds."
    }

    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $mavenArgs = @(
        "-pl", "backend",
        "-Dtest=ReviewedDev45RerankAblationTest",
        "-Drag.dev45.rerank.ablation=true",
        "-Drag.dev45.caseFile=$CaseFile",
        "-Drag.dev45.candidateK=$CandidateK",
        "-Drag.dev45.finalK=$FinalK",
        "-Drag.dev45.remote.failOpen=$RemoteFailOpen",
        "-Drag.dev45.threshold.remote.enabled=$RemoteThresholdEnabled",
        "-Drag.dev45.threshold.remote.minScore=$RemoteThresholdMinScore",
        "-Drag.dev45.threshold.local.enabled=$LocalThresholdEnabled",
        "-Drag.dev45.threshold.local.minScore=$LocalThresholdMinScore",
        "-Drag.dev45.composite.enabled=$CompositeEnabled",
        "-Drag.dev45.composite.version=$CompositeVersion",
        "-Drag.dev45.composite.baseWeight=$CompositeBaseWeight",
        "-Drag.dev45.composite.rerankWeight=$CompositeRerankWeight",
        "-Drag.dev45.output=$resultOutput",
        "-Drag.eval.datasetDir=$resolvedDatasetDir",
        "-Drag.eval.queryMode=$QueryMode",
        "-Drag.eval.milvusHost=127.0.0.1",
        "-Drag.eval.milvusPort=39530",
        "-Drag.eval.milvusCollection=rag_dev45_rerank_eval_v1",
        "-Drag.eval.milvusIndexType=FLAT",
        "test"
    )
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Dev45 rerank ablation failed; Maven exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $resultOutput -PathType Leaf)) {
        throw "Dev45 rerank ablation did not create its aggregate report."
    }
    Write-Output "Dev45 rerank ablation report: $resultOutput"
} finally {
    if ($composeStarted) {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "SilentlyContinue"
        & docker compose -p rag-dev45-rerank-eval -f (Join-Path $projectRoot "backend/evals/docker-compose.public-eval.yml") down --remove-orphans 2>$null | Out-Null
        $ErrorActionPreference = $previousErrorAction
    }
    Pop-Location
}
