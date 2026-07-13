$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'limiter-run-support.ps1')

$causalBoundary = Get-CapacityReportability -Purpose 'causal-ab' -P95Ms 3000 `
    -Drained:$true -CompletionRatio 0.99 -GatlingKoPercent 0.999 `
    -FinalInFlight 0 -InvalidSuccessAfterFailedCheck 0
if (-not $causalBoundary.reportable) { throw 'Capacity reportability rejected the exact causal P95 boundary.' }
$causalFailure = Get-CapacityReportability -Purpose 'causal-ab' -P95Ms 3001 `
    -Drained:$true -CompletionRatio 1.0 -GatlingKoPercent 0.0 `
    -FinalInFlight 0 -InvalidSuccessAfterFailedCheck 0
if ($causalFailure.reportable -or $causalFailure.reason -ne 'causal-p95-gate-failed') {
    throw 'Capacity reportability did not preserve a causal P95 failure as a valid non-reportable result.'
}
$sensitivity = Get-CapacityReportability -Purpose 'sensitivity' -P95Ms 4401 `
    -Drained:$true -CompletionRatio 1.0 -GatlingKoPercent 0.0 `
    -FinalInFlight 0 -InvalidSuccessAfterFailedCheck 0
if (-not $sensitivity.reportable) { throw 'Capacity reportability incorrectly applied the causal P95 gate to sensitivity.' }

function Assert-ArtifactSettlementRejected([string]$RunDirectory, [string]$FailureMessage) {
    $rejected = $false
    try {
        & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $RunDirectory | Out-Null
    } catch {
        $rejected = $true
    }
    if (-not $rejected) { throw $FailureMessage }
}

$runPath = & (Join-Path $PSScriptRoot 'run-entry-rate-limit.ps1') -DryRun |
    Select-Object -Last 1
$temp = Join-Path $env:TEMP ('chatagent-harness-contract-' + [guid]::NewGuid().ToString('N'))
Copy-Item -LiteralPath $runPath -Destination $temp -Recurse

try {
    $manifestPath = Join-Path $temp 'manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.effectiveConfiguration.entryEnabled = $false
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
    $mismatchRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $temp | Out-Null
    } catch {
        $mismatchRejected = $true
    }
    if (-not $mismatchRejected) { throw 'Validator accepted a mismatched limiter mode.' }

    $manifest.effectiveConfiguration.entryEnabled = $true
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
    $resultPath = Join-Path $temp 'result.json'
    $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    $result.invalidSuccessAfterFailedCheck = 1
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath
    $failedCheckRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $temp | Out-Null
    } catch {
        $failedCheckRejected = $true
    }
    if (-not $failedCheckRejected) { throw 'Validator accepted success after a failed check.' }

    $result.invalidSuccessAfterFailedCheck = 0
    $result.submitted = 100
    $result.successful = 98
    $result.terminalFailed = 2
    $result.completionRatio = 0.98
    $result.reportable = $true
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultPath
    $weakReportRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $temp | Out-Null
    } catch {
        $weakReportRejected = $true
    }
    if (-not $weakReportRejected) { throw 'Validator accepted a sub-99% reportable run.' }

    $nonLoopbackRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'new-run-artifacts.ps1') `
            -Scenario 'RoutingResilienceSimulation' `
            -Profile 'resilience-test' `
            -EntryEnabled:$false `
            -AgentRunEnabled:$false `
            -AgentRunMax $null `
            -OverrideSource 'contract-test' `
            -FixtureBaseUrl 'https://example.com' `
            -DryRun | Out-Null
    } catch {
        $nonLoopbackRejected = $true
    }
    if (-not $nonLoopbackRejected) { throw 'Runner accepted a non-loopback fixture.' }

    $capacityPath = & (Join-Path $PSScriptRoot 'run-capacity-matrix.ps1') `
        -Mode Single -MatrixRow B0 -Repetition 1 -StubLatencyMs 300 `
        -ConcurrentUsers 20 -SinglePurpose Formal -DryRun | Select-Object -Last 1
    $capacityTemp = Join-Path $env:TEMP ('chatagent-capacity-contract-' + [guid]::NewGuid().ToString('N'))
    Copy-Item -LiteralPath $capacityPath -Destination $capacityTemp -Recurse
    $capacityManifestPath = Join-Path $capacityTemp 'manifest.json'
    $capacityManifest = Get-Content -Raw -LiteralPath $capacityManifestPath | ConvertFrom-Json
    $capacityManifest.dryRun = $false
    $capacityManifest.effectiveConfiguration.concurrentUsers = 40
    $capacityManifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $capacityManifestPath
    $formalOverrideRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $capacityTemp | Out-Null
    } catch {
        $formalOverrideRejected = $true
    }
    if (-not $formalOverrideRejected) { throw 'Validator accepted an overridden formal workload.' }

    $capacityManifest.effectiveConfiguration.concurrentUsers = 20
    $capacityManifest.effectiveConfiguration.entryEnabled = $true
    $capacityManifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $capacityManifestPath
    $capacityLimiterRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $capacityTemp | Out-Null
    } catch {
        $capacityLimiterRejected = $true
    }
    if (-not $capacityLimiterRejected) { throw 'Validator accepted a limiter-enabled formal capacity run.' }

    $capacityManifest.effectiveConfiguration.entryEnabled = $false
    $capacityManifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $capacityManifestPath
    $capacityResultPath = Join-Path $capacityTemp 'result.json'
    $capacityResult = Get-Content -Raw -LiteralPath $capacityResultPath | ConvertFrom-Json
    $capacityResult.submitted = 100
    $capacityResult.successful = 100
    $capacityResult.terminalFailed = 0
    $capacityResult.timedOut = 0
    $capacityResult.interrupted = 0
    $capacityResult.finalInFlight = 0
    $capacityResult.reconciled = $true
    $capacityResult.completionRatio = 1.0
    $capacityResult.gatlingKoPercent = 0.0
    $capacityResult.invalidSuccessAfterFailedCheck = 0
    $capacityResult.reportable = $true
    $capacityResult.reason = 'all-reportability-gates-passed'
    $capacityResult | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityResultPath
    @{ schemaVersion = 1; samples = @([ordered]@{ elapsedMs = 0; ready = 0; unacknowledged = 0 }); finalReady = 0; finalUnacknowledged = 0 } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $capacityTemp 'queue.json')
    @{ schemaVersion = 1; samples = @([ordered]@{ elapsedMs = 0; backendCpuTotalMs = 0; backendWorkingSetBytes = 1; availableMemoryBytes = 1 }); logicalProcessorCount = 1; coLocatedLoadGenerator = $true } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $capacityTemp 'resources.json')
    @{ schemaVersion = 1; redis = [ordered]@{ finalPermitCardinality = 0 }; fixture = @(); circuit = @(); latency = [ordered]@{ p50Ms = 1000; p95Ms = 3001; p99Ms = 3100 } } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $capacityTemp 'observations.json')
    @{ schemaVersion = 1; ownedPids = @(); cleanupVerified = $true } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $capacityTemp 'processes.json')

    $forgedP95Rejected = $false
    try {
        & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $capacityTemp | Out-Null
    } catch {
        $forgedP95Rejected = $true
    }
    if (-not $forgedP95Rejected) { throw 'Validator accepted a reportable causal run above the P95 gate.' }

    $capacityResult.reportable = $false
    $capacityResult.reason = 'causal-p95-gate-failed'
    $capacityResult | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityResultPath
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $capacityTemp | Out-Null

    $capacityResult.reportable = $true
    $capacityResult.reason = 'all-reportability-gates-passed'
    $capacityResult | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityResultPath
    $capacityObservationsPath = Join-Path $capacityTemp 'observations.json'
    $capacityObservations = Get-Content -Raw -LiteralPath $capacityObservationsPath | ConvertFrom-Json
    $capacityObservations.latency.p95Ms = 3000
    $capacityObservations | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityObservationsPath
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $capacityTemp | Out-Null

    $capacityObservations.latency.p50Ms = $null
    $capacityObservations | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityObservationsPath
    Assert-ArtifactSettlementRejected $capacityTemp 'Validator accepted missing capacity latency evidence.'

    $capacityObservations.latency.p50Ms = 1000
    $capacityObservations.latency.p95Ms = -1
    $capacityObservations | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityObservationsPath
    Assert-ArtifactSettlementRejected $capacityTemp 'Validator accepted a negative capacity percentile.'

    $capacityObservations.latency.p95Ms = 3000
    $capacityObservations.latency.p99Ms = 2999
    $capacityObservations | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityObservationsPath
    Assert-ArtifactSettlementRejected $capacityTemp 'Validator accepted unordered capacity percentiles.'

    $capacityObservations.latency.p99Ms = 3100
    $capacityObservations | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $capacityObservationsPath
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $capacityTemp | Out-Null
} finally {
    $resolved = [System.IO.Path]::GetFullPath($temp)
    $tempRoot = [System.IO.Path]::GetFullPath($env:TEMP)
    if (-not $resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clean a contract-test path outside the OS temp directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    if ($capacityTemp) {
        $resolvedCapacity = [System.IO.Path]::GetFullPath($capacityTemp)
        if (-not $resolvedCapacity.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean a capacity contract path outside the OS temp directory.'
        }
        Remove-Item -LiteralPath $resolvedCapacity -Recurse -Force
    }
    $artifactRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\artifacts'))
    foreach ($generatedRun in @($runPath, $capacityPath)) {
        if (-not $generatedRun) { continue }
        $resolvedRun = [System.IO.Path]::GetFullPath($generatedRun)
        if (-not $resolvedRun.StartsWith($artifactRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean a generated contract run outside the artifact root.'
        }
        Remove-Item -LiteralPath $resolvedRun -Recurse -Force
    }
}

Write-Output 'HARNESS_CONTRACT_PASS'
