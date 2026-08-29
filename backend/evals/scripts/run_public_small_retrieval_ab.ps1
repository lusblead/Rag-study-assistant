param(
    [string]$DatasetDir = "backend/evals/datasets/public-small-v1",
    [string]$Output = "backend/evals/reports/public-small-v1-no-rerank-vs-local-lexical.md"
)

# Runs a deterministic, credential-free retrieval A/B over public-small-v1.
# The only A/B variable is NoOpKnowledgeReranker versus
# LocalLexicalKnowledgeReranker(0.7, 0.3).
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path

function Invoke-PublicSmallRetrievalAb {
    Push-Location $projectRoot
    try {
        $javaVersionOutput = & java -version 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Java is unavailable. Configure JDK 21 or newer before running."
        }
        $versionLine = ($javaVersionOutput | Select-Object -First 1).ToString()
        if ($versionLine -notmatch 'version\s+"(?<major>\d+)') {
            throw "Unable to determine Java version from: $versionLine"
        }
        if ([int]$Matches.major -lt 21) {
            throw "JDK 21 or newer is required; detected major version $($Matches.major)."
        }

        $resolvedDatasetDir = (Resolve-Path -LiteralPath $DatasetDir).Path
        $outputPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $Output))
        $outputParent = Split-Path -Parent $outputPath
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null

        $mavenArgs = @(
            "-o",
            "-pl", "backend",
            "-Dtest=RetrievalMetricsCalculatorTest,PublicSmallDatasetValidationTest,PublicSmallRetrievalAbTest",
            "-Drag.public.small.datasetDir=$resolvedDatasetDir",
            "-Drag.public.small.output=$outputPath",
            "test"
        )
        & mvn @mavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Public small retrieval A/B failed; Maven exit code $LASTEXITCODE."
        }
        if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
            throw "A/B report was not created: $outputPath"
        }

        $report = Get-Content -Raw -Encoding UTF8 -LiteralPath $outputPath
        foreach ($requiredHeading in @(
            "# public-small-v1 Retrieval A/B",
            "## Frozen configuration",
            "## Per-case results",
            "## Overall metrics",
            "## Regressed cases",
            "## Evidence boundary")) {
            if (-not $report.Contains($requiredHeading)) {
                throw "A/B report is missing required section: $requiredHeading"
            }
        }

        Write-Output "Public small retrieval A/B report: $Output"
    } finally {
        Pop-Location
    }
}

Invoke-PublicSmallRetrievalAb
