[CmdletBinding(DefaultParameterSetName = 'Validate')]
param(
    [Parameter(ParameterSetName = 'Validate')]
    [switch]$ValidateOnly,

    [Parameter(Mandatory = $true, ParameterSetName = 'Execute')]
    [switch]$Execute,

    [string]$Profile = (Join-Path $PSScriptRoot 'performance-profile.example.json'),
    [string]$Jmx = (Join-Path $PSScriptRoot 'rag-layered-performance.jmx'),
    [string]$Schema = (Join-Path $PSScriptRoot 'performance-report-v1.schema.json'),
    [string]$Jmeter,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'runs')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$arguments = @(
    '-X', 'utf8',
    (Join-Path $PSScriptRoot 'run_performance.py'),
    '--profile', $Profile,
    '--jmx', $Jmx,
    '--schema', $Schema,
    '--output-root', $OutputRoot
)

if ($Execute) {
    $arguments += '--execute'
} else {
    $arguments += '--validate-only'
}

if ($Jmeter) {
    $arguments += @('--jmeter', $Jmeter)
}

& python @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
