param(
    [Parameter(Mandatory = $true)][string]$RunDirectory,
    [Parameter(Mandatory = $true)][ValidateSet('harness-invalid', 'infrastructure-invalid', 'evidence-invalid')]
    [string]$Classification,
    [Parameter(Mandatory = $true)][string]$Reason
)

$ErrorActionPreference = 'Stop'
$artifactRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\artifacts'))
$resolved = [System.IO.Path]::GetFullPath($RunDirectory)
if (-not $resolved.StartsWith($artifactRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to invalidate a run outside the Gatling artifact root.'
}

$manifestPath = Join-Path $resolved 'manifest.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$resultPath = Join-Path $resolved 'result.json'
$preservedResultPath = Join-Path $resolved 'pre-invalidation-result.json'
if ((Test-Path -LiteralPath $resultPath) -and -not (Test-Path -LiteralPath $preservedResultPath)) {
    Copy-Item -LiteralPath $resultPath -Destination $preservedResultPath
}
$manifest.completedAt = (Get-Date).ToUniversalTime().ToString('o')
$manifest | Add-Member -NotePropertyName invalidClassification -NotePropertyValue $Classification -Force
$manifest | Add-Member -NotePropertyName invalidReason -NotePropertyValue $Reason -Force
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath

@{
    schemaVersion = 1; submitted = 0; successful = 0; terminalFailed = 0
    timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true
    completionRatio = 1.0; gatlingKoPercent = 100.0
    invalidSuccessAfterFailedCheck = 0; reportable = $false
    reason = "$Classification-aborted"
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultPath
@{ schemaVersion = 1; ownedPids = @(); cleanupVerified = $true } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $resolved 'processes.json')

& (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $resolved
exit $LASTEXITCODE
