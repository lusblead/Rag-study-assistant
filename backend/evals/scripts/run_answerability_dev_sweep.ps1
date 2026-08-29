param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetDir,

    [string]$PredictorClass = "com.rag.backend.agent.evaluation.decision.ProductionEvidenceDecisionPredictor",

    [string]$OutputDir = "backend/evals/runs",

    [string]$JavaHome = ""
)

# This entry point only replays a frozen, human-reviewed DEVELOPMENT snapshot.
# It does not call an embedding provider, vector store, model, API, or service.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path

function Resolve-TaskPath([string]$Value) {
    if ([IO.Path]::IsPathRooted($Value)) {
        return [IO.Path]::GetFullPath($Value)
    }
    return [IO.Path]::GetFullPath((Join-Path $projectRoot $Value))
}

if ([string]::IsNullOrWhiteSpace($PredictorClass)) {
    throw "PredictorClass must not be blank."
}

$resolvedDatasetDir = Resolve-TaskPath $DatasetDir
if (-not (Test-Path -LiteralPath $resolvedDatasetDir -PathType Container)) {
    throw "Formal answerability dataset directory is unavailable."
}
if (-not (Test-Path -LiteralPath (Join-Path $resolvedDatasetDir "manifest.json") -PathType Leaf)) {
    throw "Formal answerability dataset manifest.json is missing."
}

$resolvedOutputDir = Resolve-TaskPath $OutputDir
if (-not (Test-Path -LiteralPath $resolvedOutputDir -PathType Container)) {
    New-Item -ItemType Directory -Path $resolvedOutputDir | Out-Null
}

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $resolvedJavaHome = Resolve-TaskPath $JavaHome
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedJavaHome "bin\java.exe") -PathType Leaf)) {
        throw "JavaHome does not contain bin\java.exe."
    }
    $env:JAVA_HOME = $resolvedJavaHome
}

$maven = Join-Path $projectRoot "backend\mvnw.cmd"
if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
    throw "backend Maven wrapper is unavailable."
}

$stamp = (Get-Date).ToString("yyyy-MM-ddTHH-mm-ssfff")
$resultOutput = Join-Path $resolvedOutputDir "$stamp-answerability-dev-sweep.json"
$mavenArgs = @(
    "-q",
    "-pl", "backend",
    "-Dtest=AnswerabilityDecisionSweepHarnessTest",
    "-Danswerability.eval.enabled=true",
    "-Danswerability.eval.datasetDir=$resolvedDatasetDir",
    "-Danswerability.eval.predictorClass=$PredictorClass",
    "-Danswerability.eval.output=$resultOutput",
    "test"
)

Push-Location $projectRoot
try {
    & $maven @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Answerability Dev sweep failed; no completed result may be claimed."
    }
} finally {
    Pop-Location
}

Write-Output "COMPLETED aggregate report: $resultOutput"
