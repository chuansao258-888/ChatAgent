param(
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][string]$Profile,
    [Parameter(Mandatory = $true)][bool]$EntryEnabled,
    [Parameter(Mandatory = $true)][bool]$AgentRunEnabled,
    [AllowNull()][Nullable[int]]$AgentRunMax,
    [Parameter(Mandatory = $true)][string]$OverrideSource,
    [string]$Protocol = 'dry-run-v1',
    [switch]$DryRun,
    [string]$FixtureBaseUrl,
    [AllowNull()][Nullable[int]]$FailureThreshold,
    [AllowNull()][Nullable[int]]$OpenDurationMs,
    [AllowNull()][Nullable[int]]$HalfOpenFlightTimeoutMs,
    [AllowNull()][Nullable[bool]]$AgentMqDispatcherEnabled,
    [string]$ArtifactRoot = (Join-Path $PSScriptRoot '..\artifacts')
)

$ErrorActionPreference = 'Stop'

if ($FixtureBaseUrl) {
    $uri = [Uri]$FixtureBaseUrl
    $addresses = [System.Net.Dns]::GetHostAddresses($uri.DnsSafeHost)
    if (-not $addresses -or ($addresses | Where-Object { -not [System.Net.IPAddress]::IsLoopback($_) })) {
        throw "Fixture target must resolve only to loopback addresses: $FixtureBaseUrl"
    }
}

$runId = '{0}-{1}-{2}' -f $Scenario.ToLowerInvariant(), (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
$runDir = New-Item -ItemType Directory -Force -Path (Join-Path $ArtifactRoot $runId)
$startedAt = (Get-Date).ToUniversalTime().ToString('o')
$commit = (& git rev-parse HEAD).Trim()
$dirty = [bool](& git status --porcelain)

$manifest = [ordered]@{
    schemaVersion = 1
    runId = $runId
    scenario = $Scenario
    profile = $Profile
    protocol = $Protocol
    dryRun = [bool]$DryRun
    startedAt = $startedAt
    completedAt = $startedAt
    git = [ordered]@{ commit = $commit; dirty = $dirty }
    effectiveConfiguration = [ordered]@{
        entryEnabled = $EntryEnabled
        agentRunEnabled = $AgentRunEnabled
        agentRunMax = $AgentRunMax
        overrideSource = $OverrideSource
        fixtureBaseUrl = if ($FixtureBaseUrl) { $FixtureBaseUrl } else { $null }
        failureThreshold = $FailureThreshold
        openDurationMs = $OpenDurationMs
        halfOpenFlightTimeoutMs = $HalfOpenFlightTimeoutMs
        agentMqDispatcherEnabled = $AgentMqDispatcherEnabled
    }
    safety = [ordered]@{
        realProviderCredentialRead = $false
        providerTargetLoopback = if ($FixtureBaseUrl) { $true } else { $null }
    }
    artifacts = @('result.json', 'turn-samples.json', 'queue.json', 'resources.json', 'observations.json', 'processes.json', 'run.log', 'hashes.json')
}

$result = [ordered]@{
    schemaVersion = 1
    submitted = 0
    successful = 0
    terminalFailed = 0
    timedOut = 0
    interrupted = 0
    finalInFlight = 0
    reconciled = $true
    completionRatio = 1.0
    gatlingKoPercent = 0.0
    invalidSuccessAfterFailedCheck = 0
    reportable = $false
    reason = if ($DryRun) { 'dry-run' } else { 'initialized' }
}

$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDir 'manifest.json')
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDir 'result.json')
@{ schemaVersion = 1; samples = @() } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'turn-samples.json')
@{ schemaVersion = 1; samples = @(); finalReady = 0; finalUnacknowledged = 0 } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'queue.json')
@{ schemaVersion = 1; samples = @() } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'resources.json')
@{ schemaVersion = 1; redis = @(); fixture = @(); circuit = @() } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'observations.json')
@{ schemaVersion = 1; ownedPids = @(); cleanupVerified = $true } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDir 'processes.json')
$logMessage = if ($DryRun) {
    'dry-run: no backend, fixture, sampler, or Gatling process started'
} else {
    'initialized: owning runner will append sanitized execution events'
}
$logMessage |
    Set-Content -LiteralPath (Join-Path $runDir 'run.log')

& (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $runDir.FullName | Out-Null
& (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $runDir.FullName | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Output $runDir.FullName
