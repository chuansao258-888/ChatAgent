param(
    [string]$ArtifactRoot = (Join-Path $PSScriptRoot '..\artifacts'),
    [string]$CapacityIndexPath = (Join-Path $PSScriptRoot '..\CAPACITY_RUN_INDEX.json'),
    [string]$HistoryIndexPath = (Join-Path $PSScriptRoot '..\ARTIFACT_RETENTION_INDEX.json')
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($ArtifactRoot)
if (-not (Test-Path -LiteralPath $CapacityIndexPath)) {
    throw 'Capacity attempt index must be published before raw artifact cleanup.'
}

$fixedKeep = @(
    'entryratelimitsimulation-20260713-075002-8a6f5663',
    'entryratelimitsimulation-20260713-075037-c24d19a3',
    'agentcapacitysimulation-20260713-072340-3302a506',
    'agentcapacitysimulation-20260713-072544-9d157193',
    'agentredisfailureaudit-20260713-075702-ea5fbe11',
    'routingresiliencesimulation-20260713-085042-cdd39901'
)
$historyByRunId = @{}
if (Test-Path -LiteralPath $HistoryIndexPath) {
    $existingHistory = Get-Content -Raw -LiteralPath $HistoryIndexPath | ConvertFrom-Json
    foreach ($run in @($existingHistory.runs)) { $historyByRunId[[string]$run.runId] = $run }
}
$keep = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($name in $fixedKeep) { [void]$keep.Add($name) }

foreach ($directory in Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name) {
    $manifestPath = Join-Path $directory.FullName 'manifest.json'
    $resultPath = Join-Path $directory.FullName 'result.json'
    if (-not (Test-Path -LiteralPath $manifestPath) -or -not (Test-Path -LiteralPath $resultPath)) { continue }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    $acceptedCapacity = $manifest.scenario -eq 'ChatTurnCapacitySimulation' -and
        -not $manifest.dryRun -and -not $manifest.invalidClassification -and
        $manifest.effectiveConfiguration.workloadVersion -eq 'single-turn-closed-v3'
    if ($acceptedCapacity) { [void]$keep.Add($directory.Name) }
    $hashPath = Join-Path $directory.FullName 'hashes.json'
    $historyByRunId[[string]$manifest.runId] = [ordered]@{
            runId = $manifest.runId
            scenario = $manifest.scenario
            reportable = [bool]$result.reportable
            retainedRaw = $keep.Contains($directory.Name)
            invalidClassification = $manifest.invalidClassification
            resultReason = $result.reason
            artifactDirectory = $directory.Name
            hashesSha256 = if (Test-Path -LiteralPath $hashPath) {
                (Get-FileHash -LiteralPath $hashPath -Algorithm SHA256).Hash.ToLowerInvariant()
            } else { $null }
        }
}

$historyGeneratedAt = if ($existingHistory -and $existingHistory.generatedAt) {
    [string]$existingHistory.generatedAt
} else {
    (Get-Date).ToUniversalTime().ToString('o')
}
[ordered]@{
    schemaVersion = 1
    generatedAt = $historyGeneratedAt
    capacityAttemptIndexSha256 = (Get-FileHash -LiteralPath $CapacityIndexPath -Algorithm SHA256).Hash.ToLowerInvariant()
    runs = @($historyByRunId.GetEnumerator() | Sort-Object Key | ForEach-Object { $_.Value })
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $HistoryIndexPath

foreach ($directory in Get-ChildItem -LiteralPath $root -Directory) {
    if ($keep.Contains($directory.Name)) { continue }
    $resolved = [System.IO.Path]::GetFullPath($directory.FullName)
    if (-not $resolved.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove artifact outside root: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

$remaining = @(Get-ChildItem -LiteralPath $root -Directory | Select-Object -ExpandProperty Name)
if ((Compare-Object @($keep | Sort-Object) @($remaining | Sort-Object)).Count -ne 0) {
    throw 'Raw artifact cleanup did not leave exactly the accepted keep set.'
}
Write-Output "ARTIFACT_CLEANUP_PASS retained=$($remaining.Count) indexed=$($historyByRunId.Count)"
