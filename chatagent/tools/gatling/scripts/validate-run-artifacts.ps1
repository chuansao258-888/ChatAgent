param(
    [Parameter(Mandatory = $true)][string]$RunDirectory
)

$ErrorActionPreference = 'Stop'
$manifestPath = Join-Path $RunDirectory 'manifest.json'
$resultPath = Join-Path $RunDirectory 'result.json'
if (-not (Test-Path -LiteralPath $manifestPath) -or -not (Test-Path -LiteralPath $resultPath)) {
    throw 'Run artifacts must contain manifest.json and result.json.'
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $result.schemaVersion -ne 1) {
    throw 'Unsupported run artifact schema version.'
}

$matrix = @{
    ChatTurnCapacitySimulation = @{ profile = 'capacity-test'; entry = $false; agent = $false; max = $null }
    EntryRateLimitSimulation = @{ profile = 'capacity-test'; entry = $true; agent = $false; max = $null }
    AgentCapacitySimulation = @{ profile = 'capacity-test'; entry = $false; agent = $true; max = 3 }
    RoutingResilienceSimulation = @{ profile = 'resilience-test'; entry = $null; agent = $null; max = $null }
}
$expected = $matrix[$manifest.scenario]
if (-not $expected) { throw "Unknown scenario: $($manifest.scenario)" }
if ($manifest.profile -ne $expected.profile) { throw 'Scenario/profile mismatch.' }

if ($manifest.scenario -ne 'RoutingResilienceSimulation') {
    $actual = $manifest.effectiveConfiguration
    if ([bool]$actual.entryEnabled -ne $expected.entry -or
        [bool]$actual.agentRunEnabled -ne $expected.agent -or
        ($null -ne $expected.max -and [int]$actual.agentRunMax -ne $expected.max) -or
        ($null -eq $expected.max -and $null -ne $actual.agentRunMax)) {
        throw 'Effective limiter mode differs from the authoritative scenario matrix.'
    }
}

$total = [long]$result.successful + [long]$result.terminalFailed + [long]$result.timedOut + [long]$result.interrupted + [long]$result.finalInFlight
if ($total -ne [long]$result.submitted) { throw 'Turn outcomes do not reconcile to submitted.' }
if ([long]$result.invalidSuccessAfterFailedCheck -ne 0) { throw 'A success sample was recorded after a failed HTTP/SSE check.' }
if (-not $result.reconciled) { throw 'Run result is not reconciled.' }
$expectedRatio = if ([long]$result.submitted -eq 0L) {
    1.0
} else {
    [double]$result.successful / [double]$result.submitted
}
if ([math]::Abs([double]$result.completionRatio - $expectedRatio) -gt 0.000001) {
    throw 'Completion ratio does not match reconciled outcome counts.'
}
if ($result.reportable) {
    if ([long]$result.finalInFlight -ne 0L -or
        [double]$result.completionRatio -lt 0.99 -or
        [double]$result.gatlingKoPercent -ge 1.0) {
        throw 'Reportable run violates completion, drain, or Gatling KO gates.'
    }
}

$required = @('turn-samples.json', 'queue.json', 'resources.json', 'observations.json', 'processes.json', 'run.log', 'hashes.json')
foreach ($name in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $RunDirectory $name))) {
        throw "Missing required artifact: $name"
    }
}
$processes = Get-Content -Raw -LiteralPath (Join-Path $RunDirectory 'processes.json') | ConvertFrom-Json
if (-not $processes.cleanupVerified -or @($processes.ownedPids).Count -ne 0) {
    throw 'Owned process cleanup is incomplete.'
}

$hashManifest = Get-Content -Raw -LiteralPath (Join-Path $RunDirectory 'hashes.json') | ConvertFrom-Json
$expectedHashFiles = Get-ChildItem -LiteralPath $RunDirectory -File |
    Where-Object Name -ne 'hashes.json' |
    Sort-Object Name
if (@($hashManifest.files).Count -ne @($expectedHashFiles).Count) {
    throw 'Artifact hash inventory is incomplete.'
}
foreach ($file in $expectedHashFiles) {
    $entry = @($hashManifest.files | Where-Object file -eq $file.Name)
    $actualHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($entry.Count -ne 1 -or $entry[0].sha256 -ne $actualHash) {
        throw "Artifact hash mismatch: $($file.Name)"
    }
}

Write-Output "VALID $($manifest.runId)"
