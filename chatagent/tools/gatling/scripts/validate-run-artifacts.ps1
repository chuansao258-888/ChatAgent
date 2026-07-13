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
    AgentRedisFailureAudit = @{ profile = 'capacity-test'; entry = $false; agent = $true; max = 3 }
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
if ($manifest.scenario -eq 'ChatTurnCapacitySimulation' -and -not $manifest.dryRun -and
    -not $manifest.invalidClassification) {
    $actual = $manifest.effectiveConfiguration
    if ($actual.workloadVersion -ne 'single-turn-closed-v3') {
        throw 'Capacity run does not use the authoritative single-turn closed workload.'
    }
    $rows = @{
        B0 = @{ agent = 5; pool = 10; poll = 500; batch = 10 }
        B1 = @{ agent = 20; pool = 10; poll = 500; batch = 10 }
        B2 = @{ agent = 20; pool = 30; poll = 500; batch = 10 }
        B3 = @{ agent = 20; pool = 30; poll = 100; batch = 50 }
    }
    if ($actual.experimentPurpose -eq 'calibration') {
        if ([int]$actual.stubLatencyMs -ne 300 -or [int]$actual.holdSeconds -ne 60 -or
            [int]$actual.concurrentUsers -notin @(5, 10, 20, 40, 60) -or
            [int]$actual.repetition -ne 1 -or $actual.matrixRow -ne 'B3') {
            throw 'Calibration run differs from the authoritative ladder.'
        }
    } elseif ($actual.experimentPurpose -in @('causal-ab', 'sensitivity')) {
        $row = $rows[[string]$actual.matrixRow]
        if (-not $row -or [int]$actual.concurrentUsers -ne 20 -or
            [int]$actual.holdSeconds -ne 300 -or [int]$actual.repetition -notin 1..3 -or
            [int]$actual.agentConcurrency -ne $row.agent -or
            [int]$actual.hikariMaximumPoolSize -ne $row.pool -or
            [int]$actual.outboxPollIntervalMs -ne $row.poll -or
            [int]$actual.outboxBatchSize -ne $row.batch) {
            throw 'Formal capacity run differs from the authoritative protocol or B0-B3 matrix.'
        }
        if (($actual.experimentPurpose -eq 'causal-ab' -and [int]$actual.stubLatencyMs -ne 300) -or
            ($actual.experimentPurpose -eq 'sensitivity' -and
             ($actual.matrixRow -ne 'B3' -or [int]$actual.stubLatencyMs -notin @(1200, 3000)))) {
            throw 'Formal capacity latency/purpose pairing is invalid.'
        }
    } else {
        throw 'Capacity run is missing an authoritative experiment purpose.'
    }
}
if ($manifest.scenario -eq 'RoutingResilienceSimulation' -and -not $manifest.dryRun) {
    $actual = $manifest.effectiveConfiguration
    if ([int]$actual.failureThreshold -ne 4 -or [int]$actual.openDurationMs -ne 5000 -or
        [int]$actual.halfOpenFlightTimeoutMs -ne 5000 -or
        [bool]$actual.agentMqDispatcherEnabled) {
        throw 'Routing resilience campaign configuration differs from the authoritative matrix.'
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
    $queue = Get-Content -Raw -LiteralPath (Join-Path $RunDirectory 'queue.json') | ConvertFrom-Json
    $observations = Get-Content -Raw -LiteralPath (Join-Path $RunDirectory 'observations.json') | ConvertFrom-Json
    if ([long]$queue.finalReady -ne 0L -or [long]$queue.finalUnacknowledged -ne 0L -or
        ($manifest.scenario -eq 'ChatTurnCapacitySimulation' -and [long]$observations.redis.finalPermitCardinality -ne 0L)) {
        throw 'Reportable run violates RabbitMQ or Redis drain gates.'
    }
    if ($manifest.scenario -eq 'ChatTurnCapacitySimulation') {
        $resources = Get-Content -Raw -LiteralPath (Join-Path $RunDirectory 'resources.json') | ConvertFrom-Json
        $resourceSamples = @($resources.samples)
        if ([int]$resources.logicalProcessorCount -le 0 -or $resourceSamples.Count -eq 0 -or
            @($resourceSamples | Where-Object {
                    $null -eq $_.backendCpuTotalMs -or $null -eq $_.backendWorkingSetBytes -or
                    $null -eq $_.availableMemoryBytes
                }).Count -gt 0) {
            throw 'Reportable capacity run lacks CPU, memory, or JVM resource evidence.'
        }
        if ($null -eq $observations.latency -or
            $null -eq $observations.latency.p50Ms -or
            $null -eq $observations.latency.p95Ms -or
            $null -eq $observations.latency.p99Ms) {
            throw 'Reportable capacity run lacks P50, P95, or P99 latency evidence.'
        }
        $p50 = [double]$observations.latency.p50Ms
        $p95 = [double]$observations.latency.p95Ms
        $p99 = [double]$observations.latency.p99Ms
        if ([double]::IsNaN($p50) -or [double]::IsInfinity($p50) -or
            [double]::IsNaN($p95) -or [double]::IsInfinity($p95) -or
            [double]::IsNaN($p99) -or [double]::IsInfinity($p99) -or
            $p50 -lt 0 -or $p95 -lt 0 -or $p99 -lt 0 -or
            $p50 -ne [math]::Floor($p50) -or $p95 -ne [math]::Floor($p95) -or
            $p99 -ne [math]::Floor($p99) -or $p50 -gt $p95 -or $p95 -gt $p99) {
            throw 'Reportable capacity latency percentiles must be finite, non-negative, integral, and ordered P50 <= P95 <= P99.'
        }
        if ($manifest.effectiveConfiguration.experimentPurpose -eq 'causal-ab' -and
            $p95 -gt 3000L) {
            throw 'Reportable 300 ms causal run violates the E2E P95 headline gate.'
        }
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
if ((Compare-Object @($manifest.artifacts | Sort-Object) @($expectedHashFiles.Name)).Count -ne 0) {
    throw 'Manifest artifact inventory does not match the run directory.'
}
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
