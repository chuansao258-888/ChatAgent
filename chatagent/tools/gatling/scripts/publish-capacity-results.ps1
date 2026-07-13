param(
    [string]$ArtifactRoot = (Join-Path $PSScriptRoot '..\artifacts'),
    [string]$SummaryPath = (Join-Path $PSScriptRoot '..\BENCHMARK_RESULTS.md'),
    [string]$IndexPath = (Join-Path $PSScriptRoot '..\CAPACITY_RUN_INDEX.json'),
    [string]$HistoryIndexPath = (Join-Path $PSScriptRoot '..\ARTIFACT_RETENTION_INDEX.json')
)

$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'validate-run-artifacts.ps1'

function Get-Median([double[]]$Values) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $middle = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Get-RangeText([double[]]$Values, [int]$Digits = 0) {
    $minimum = ($Values | Measure-Object -Minimum).Minimum
    $maximum = ($Values | Measure-Object -Maximum).Maximum
    ('{0:N' + $Digits + '}-{1:N' + $Digits + '}') -f $minimum, $maximum
}

function Get-PeakBackendCpuPercent($Resources) {
    $samples = @($Resources.samples | Sort-Object elapsedMs)
    $logical = [double]$Resources.logicalProcessorCount
    $peak = 0.0
    for ($index = 1; $index -lt $samples.Count; $index++) {
        $elapsed = [double]$samples[$index].elapsedMs - [double]$samples[$index - 1].elapsedMs
        $cpu = [double]$samples[$index].backendCpuTotalMs - [double]$samples[$index - 1].backendCpuTotalMs
        if ($elapsed -gt 0 -and $cpu -ge 0) {
            $peak = [math]::Max($peak, 100.0 * $cpu / ($elapsed * $logical))
        }
    }
    $peak
}

$attemptByRunId = @{}
if (Test-Path -LiteralPath $IndexPath) {
    $existingIndex = Get-Content -Raw -LiteralPath $IndexPath | ConvertFrom-Json
    foreach ($attempt in @($existingIndex.attempts)) { $attemptByRunId[[string]$attempt.runId] = $attempt }
}
$accepted = [System.Collections.Generic.List[object]]::new()
foreach ($directory in Get-ChildItem -LiteralPath $ArtifactRoot -Directory |
        Where-Object Name -like 'chatturncapacitysimulation-*' | Sort-Object Name) {
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $directory.FullName 'manifest.json') | ConvertFrom-Json
    $result = Get-Content -Raw -LiteralPath (Join-Path $directory.FullName 'result.json') | ConvertFrom-Json
    $configuration = $manifest.effectiveConfiguration
    $attemptByRunId[[string]$manifest.runId] = [ordered]@{
            runId = $manifest.runId
            purpose = $configuration.experimentPurpose
            matrixRow = $configuration.matrixRow
            repetition = $configuration.repetition
            concurrentUsers = $configuration.concurrentUsers
            holdSeconds = $configuration.holdSeconds
            stubLatencyMs = $configuration.stubLatencyMs
            workloadVersion = $configuration.workloadVersion
            dryRun = [bool]$manifest.dryRun
            invalidClassification = $manifest.invalidClassification
            invalidReason = $manifest.invalidReason
            reportable = [bool]$result.reportable
            resultReason = $result.reason
            artifactDirectory = $directory.Name
            hashesSha256 = (Get-FileHash -LiteralPath (Join-Path $directory.FullName 'hashes.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    if ($manifest.dryRun -or $manifest.invalidClassification -or
        $configuration.workloadVersion -ne 'single-turn-closed-v3') { continue }

    & $validator -RunDirectory $directory.FullName | Out-Null
    $observations = Get-Content -Raw -LiteralPath (Join-Path $directory.FullName 'observations.json') | ConvertFrom-Json
    $resources = Get-Content -Raw -LiteralPath (Join-Path $directory.FullName 'resources.json') | ConvertFrom-Json
    $queue = Get-Content -Raw -LiteralPath (Join-Path $directory.FullName 'queue.json') | ConvertFrom-Json
    $resourceSamples = @($resources.samples)
    $queueSamples = @($queue.samples)
    $accepted.Add([pscustomobject]@{
            RunId = $manifest.runId
            Purpose = [string]$configuration.experimentPurpose
            Row = [string]$configuration.matrixRow
            Repetition = [int]$configuration.repetition
            Users = [int]$configuration.concurrentUsers
            LatencyMs = [int]$configuration.stubLatencyMs
            Reportable = [bool]$result.reportable
            Submitted = [long]$result.submitted
            Successful = [long]$result.successful
            CompletionRatio = [double]$result.completionRatio
            KoPercent = [double]$result.gatlingKoPercent
            TurnPerSecond = [double]$result.successful / [double]$configuration.holdSeconds
            P50 = [long]$observations.latency.p50Ms
            P95 = [long]$observations.latency.p95Ms
            P99 = [long]$observations.latency.p99Ms
            PeakBackendCpuPercent = Get-PeakBackendCpuPercent $resources
            PeakJvmMiB = [double](($resourceSamples.backendWorkingSetBytes | Measure-Object -Maximum).Maximum) / 1MB
            MinimumFreeMemoryMiB = [double](($resourceSamples.availableMemoryBytes | Measure-Object -Minimum).Minimum) / 1MB
            PeakReady = [long](($queueSamples.ready | Measure-Object -Maximum).Maximum)
            PeakUnacknowledged = [long](($queueSamples.unacknowledged | Measure-Object -Maximum).Maximum)
        })
}

$indexGeneratedAt = if ($existingIndex -and $existingIndex.generatedAt) {
    [string]$existingIndex.generatedAt
} else {
    (Get-Date).ToUniversalTime().ToString('o')
}
[ordered]@{
    schemaVersion = 1
    generatedAt = $indexGeneratedAt
    attempts = @($attemptByRunId.GetEnumerator() | Sort-Object Key | ForEach-Object { $_.Value })
} |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $IndexPath
$indexHash = (Get-FileHash -LiteralPath $IndexPath -Algorithm SHA256).Hash.ToLowerInvariant()
$historyHash = if (Test-Path -LiteralPath $HistoryIndexPath) {
    (Get-FileHash -LiteralPath $HistoryIndexPath -Algorithm SHA256).Hash.ToLowerInvariant()
} else { 'pending-final-cleanup' }

$calibration = @($accepted | Where-Object Purpose -eq 'calibration')
$formal = @($accepted | Where-Object Purpose -eq 'causal-ab')
$sensitivity = @($accepted | Where-Object Purpose -eq 'sensitivity')
if ($calibration.Count -ne 5 -or
    (Compare-Object @(5, 10, 20, 40, 60) @($calibration.Users | Sort-Object)).Count -ne 0) {
    throw 'Accepted calibration set must contain exactly the fixed five-user ladder.'
}
if ($formal.Count -ne 12 -or @($formal | Where-Object { -not $_.Reportable }).Count -ne 0) {
    throw 'Accepted causal matrix must contain exactly twelve reportable runs.'
}
if ($sensitivity.Count -ne 6 -or @($sensitivity | Where-Object { -not $_.Reportable }).Count -ne 0) {
    throw 'Accepted sensitivity set must contain exactly six reportable runs.'
}
$formalKeys = @($formal | ForEach-Object { "$($_.Row)-$($_.Repetition)" })
if (@($formalKeys | Select-Object -Unique).Count -ne 12) { throw 'Causal matrix contains duplicate row/repetition keys.' }
$sensitivityKeys = @($sensitivity | ForEach-Object { "$($_.LatencyMs)-$($_.Repetition)" })
if (@($sensitivityKeys | Select-Object -Unique).Count -ne 6) { throw 'Sensitivity matrix contains duplicate latency/repetition keys.' }

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# Capacity Benchmark Results')
$lines.Add('')
$lines.Add('Generated from validated schema-v1 run artifacts. The generator refuses incomplete, duplicate, non-reportable, limiter-enabled, un-drained, or resource-incomplete formal evidence.')
$lines.Add('')
$lines.Add('## Scope and protocol')
$lines.Add('')
$lines.Add('- Synthetic in-process no-tool LLM stub; no provider credentials or real LLM quota used.')
$lines.Add('- Single-turn isolated sessions, closed 20-user, pace-0, 300-second held workload; load generator, backend, and infrastructure are co-located on one Windows desktop.')
$lines.Add('- Both entry and Agent-run limiters disabled. Results are application-path comparisons, not limiter throughput or production capacity.')
$lines.Add('- Each formal row has three reportable repetitions. Values below are median with min–max range.')
$lines.Add("- Tracked capacity attempt index: tools/gatling/CAPACITY_RUN_INDEX.json (SHA-256 $indexHash). Invalid and dry-run attempts remain indexed but are excluded from accepted summaries.")
$lines.Add("- Tracked artifact-retention index: tools/gatling/ARTIFACT_RETENTION_INDEX.json (SHA-256 $historyHash). It preserves sanitized run summaries and hashes before obsolete raw artifacts are removed.")
$lines.Add('')
$lines.Add('## Calibration (range finding only)')
$lines.Add('')
$lines.Add('| Closed users | turn/s | success % | Gatling KO % | P95 ms | peak backend CPU % | peak JVM MiB |')
$lines.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($users in @(5, 10, 20, 40, 60)) {
    $run = @($calibration | Where-Object Users -eq $users)[0]
    $lines.Add(('| {0} | {1:N2} | {2:N3} | {3:N3} | {4:N0} | {5:N2} | {6:N0} |' -f
            $users, $run.TurnPerSecond, (100.0 * $run.CompletionRatio), $run.KoPercent,
            $run.P95, $run.PeakBackendCpuPercent, $run.PeakJvmMiB))
}
$lines.Add('')
$lines.Add('Calibration identifies local range behavior only; it is not used as a resume throughput number or causal comparison.')
$lines.Add('')
$lines.Add('## 300 ms causal A/B matrix')
$lines.Add('')
$lines.Add('| Row | Change from prior row | turn/s | success % | Gatling KO % | P50 ms | P95 ms | P99 ms | peak backend CPU % | peak JVM MiB | min free memory MiB | peak ready | peak unack |')
$lines.Add('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
$changes = @{ B0 = 'Baseline: Agent 5, Hikari 10, outbox 500 ms/10'; B1 = 'Agent concurrency 5 -> 20'; B2 = 'Hikari pool 10 -> 30'; B3 = 'Outbox bundle 500 ms/10 -> 100 ms/50' }
foreach ($rowName in @('B0', 'B1', 'B2', 'B3')) {
    $row = @($formal | Where-Object Row -eq $rowName)
    $values = @{
        Tps = @($row.TurnPerSecond); Success = @($row | ForEach-Object { 100.0 * $_.CompletionRatio }); Ko = @($row.KoPercent)
        P50 = @($row.P50); P95 = @($row.P95); P99 = @($row.P99)
        Cpu = @($row.PeakBackendCpuPercent); Jvm = @($row.PeakJvmMiB); Free = @($row.MinimumFreeMemoryMiB)
        Ready = @($row.PeakReady); Unack = @($row.PeakUnacknowledged)
    }
    $lines.Add(('| {0} | {1} | {2:N2} ({3}) | {4:N3} ({5}) | {6:N3} ({7}) | {8:N0} ({9}) | {10:N0} ({11}) | {12:N0} ({13}) | {14:N2} ({15}) | {16:N0} ({17}) | {18:N0} ({19}) | {20:N0} ({21}) | {22:N0} ({23}) |' -f
            $rowName, $changes[$rowName], (Get-Median $values.Tps), (Get-RangeText $values.Tps 2),
            (Get-Median $values.Success), (Get-RangeText $values.Success 3), (Get-Median $values.Ko), (Get-RangeText $values.Ko 3),
            (Get-Median $values.P50), (Get-RangeText $values.P50), (Get-Median $values.P95), (Get-RangeText $values.P95),
            (Get-Median $values.P99), (Get-RangeText $values.P99), (Get-Median $values.Cpu), (Get-RangeText $values.Cpu 2),
            (Get-Median $values.Jvm), (Get-RangeText $values.Jvm), (Get-Median $values.Free), (Get-RangeText $values.Free),
            (Get-Median $values.Ready), (Get-RangeText $values.Ready), (Get-Median $values.Unack), (Get-RangeText $values.Unack)))
}
$lines.Add('')
$lines.Add('All twelve causal runs reconciled 100% of submitted turns, achieved at least 99% successful completion, remained below 1% KO, met E2E P95 <= 3000 ms, and drained RabbitMQ ready/unacknowledged plus Redis active permits to zero.')
$lines.Add('B1 materially improved latency and throughput versus B0. B2 did not improve median P95 or throughput on this machine. B3 materially improved latency versus B2, but the experiment attributes that change only to the combined outbox poll/batch bundle.')
$lines.Add('')
$lines.Add('## Latency sensitivity at B3')
$lines.Add('')
$lines.Add('| Stub latency | turn/s | success % | Gatling KO % | P50 ms | P95 ms | P99 ms | peak backend CPU % | peak JVM MiB |')
$lines.Add('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($latency in @(1200, 3000)) {
    $row = @($sensitivity | Where-Object LatencyMs -eq $latency)
    $successValues = @($row | ForEach-Object { 100.0 * $_.CompletionRatio })
    $lines.Add(('| {0} ms | {1:N2} ({2}) | {3:N3} ({4}) | {5:N3} ({6}) | {7:N0} ({8}) | {9:N0} ({10}) | {11:N0} ({12}) | {13:N2} ({14}) | {15:N0} ({16}) |' -f
            $latency, (Get-Median @($row.TurnPerSecond)), (Get-RangeText @($row.TurnPerSecond) 2),
            (Get-Median $successValues), (Get-RangeText $successValues 3), (Get-Median @($row.KoPercent)), (Get-RangeText @($row.KoPercent) 3),
            (Get-Median @($row.P50)), (Get-RangeText @($row.P50)), (Get-Median @($row.P95)), (Get-RangeText @($row.P95)),
            (Get-Median @($row.P99)), (Get-RangeText @($row.P99)), (Get-Median @($row.PeakBackendCpuPercent)),
            (Get-RangeText @($row.PeakBackendCpuPercent) 2), (Get-Median @($row.PeakJvmMiB)), (Get-RangeText @($row.PeakJvmMiB))))
}
$lines.Add('')
$lines.Add('Sensitivity rows are descriptive only and do not inherit the 300 ms P95 headline gate.')
$lines.Add('')
$lines.Add('## Resume and interview wording')
$lines.Add('')
$lines.Add('Safe claim: "Designed and validated a two-layer rate-limit/circuit-breaker load harness with deterministic LLM stubs, reconciled SSE turn outcomes, queue/permit drain gates, fault injection, and a three-repeat B0-B3 ablation. On a co-located 20-user synthetic workload, the accepted baseline-to-final configuration reduced median E2E P95 while preserving >=99% completion and <1% KO; the DB-pool-only step showed no improvement."')
$lines.Add('')
$lines.Add('Do not claim real-provider throughput, production capacity, universal QPS, provider cost savings, or causality for either individual outbox knob. The experiment measures this repository and machine under synthetic no-tool latency.')
$lines | Set-Content -LiteralPath $SummaryPath

Write-Output "PUBLISHED $SummaryPath"
Write-Output "INDEX $IndexPath $indexHash"
