param(
    [ValidateSet('Calibration', 'Formal', 'Sensitivity', 'All', 'Single')]
    [string]$Mode = 'All',
    [ValidateSet('B0', 'B1', 'B2', 'B3')][string]$MatrixRow = 'B3',
    [ValidateSet(1, 2, 3)][int]$Repetition = 1,
    [ValidateSet(300, 1200, 3000)][int]$StubLatencyMs = 300,
    [ValidateSet(5, 10, 20, 40, 60)][int]$ConcurrentUsers = 20,
    [ValidateSet('Calibration', 'Formal', 'Sensitivity')]
    [string]$SinglePurpose = 'Formal',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'limiter-run-support.ps1')
$artifactRoot = Join-Path $PSScriptRoot '..\artifacts'
$common = Join-Path $PSScriptRoot 'new-run-artifacts.ps1'
$validator = Join-Path $PSScriptRoot 'validate-run-artifacts.ps1'
$settler = Join-Path $PSScriptRoot 'settle-run-artifacts.ps1'

$rows = [ordered]@{
    B0 = @{ agent = 5; pool = 10; poll = 500; batch = 10 }
    B1 = @{ agent = 20; pool = 10; poll = 500; batch = 10 }
    B2 = @{ agent = 20; pool = 30; poll = 500; batch = 10 }
    B3 = @{ agent = 20; pool = 30; poll = 100; batch = 50 }
}

function Get-Percentile([long[]]$Values, [double]$Quantile) {
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Max(0, [math]::Ceiling($Quantile * $sorted.Count) - 1)
    [long]$sorted[$index]
}

function Get-QueueTotals {
    $queues = @(Get-ChatAgentQueueSnapshot)
    if ($queues.Count -eq 1 -and $queues[0] -is [System.Array]) {
        $queues = @($queues[0])
    }
    $ready = 0L
    $unacknowledged = 0L
    $validQueues = 0
    foreach ($queue in $queues) {
        if ($null -ne $queue.PSObject.Properties['messages_ready'] -and
            $null -ne $queue.PSObject.Properties['messages_unacknowledged']) {
            $ready += [long]$queue.messages_ready
            $unacknowledged += [long]$queue.messages_unacknowledged
            $validQueues++
        }
    }
    if ($validQueues -eq 0) {
        $shape = @($queues | ForEach-Object { $_.GetType().FullName + ':' + ($_.PSObject.Properties.Name -join ',') }) -join ';'
        throw "RabbitMQ observation contained no queue records (count=$($queues.Count), shape=$shape)."
    }
    [ordered]@{
        ready = $ready
        unacknowledged = $unacknowledged
    }
}

function Invoke-CapacityRun {
    param([string]$Purpose, [string]$RowName, [int]$Repeat, [int]$LatencyMs,
          [int]$Users, [int]$HoldSeconds)

    $row = $rows[$RowName]
    $runDirectory = & $common -Scenario 'ChatTurnCapacitySimulation' -Profile 'capacity-test' `
        -EntryEnabled:$false -AgentRunEnabled:$false -AgentRunMax $null `
        -OverrideSource 'run-capacity-matrix.ps1/fixed-protocol' -Protocol 'formal-v1' `
        -ExperimentPurpose $Purpose -MatrixRow $RowName -Repetition $Repeat `
        -ConcurrentUsers $Users -HoldSeconds $HoldSeconds -StubLatencyMs $LatencyMs `
        -AgentConcurrency $row.agent -HikariMaximumPoolSize $row.pool `
        -OutboxPollIntervalMs $row.poll -OutboxBatchSize $row.batch `
        -WorkloadVersion 'single-turn-closed-v3' -DryRun:$DryRun |
        Select-Object -Last 1
    if ($DryRun) { return $runDirectory }

    # The initialized manifest is validated before any backend or load process starts.
    & $validator -RunDirectory $runDirectory | Out-Null
    $paths = Get-ChatAgentPaths
    Import-ChatAgentLocalEnvironment $paths.EnvironmentFile
    $env:JAVA_HOME = 'C:\Users\guany\.jdks\ms-17.0.18'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $env:SPRING_PROFILES_ACTIVE = 'capacity-test'
    $env:CHATAGENT_RATE_LIMIT_ENTRY_ENABLED = 'false'
    $env:CHATAGENT_RATE_LIMIT_AGENT_RUN_ENABLED = 'false'
    $env:CHATAGENT_MQ_CONSUMERS_AGENT_CONCURRENCY = [string]$row.agent
    $env:CHATAGENT_MQ_CONSUMERS_AGENT_PREFETCH = [string]$row.agent
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = [string]$row.pool
    $env:CHATAGENT_MQ_OUTBOX_POLL_INTERVAL_MS = [string]$row.poll
    $env:CHATAGENT_MQ_OUTBOX_BATCH_SIZE = [string]$row.batch
    $env:CHATAGENT_CAPACITY_TEST_MOCK_TTFT_MS = [string][int]($LatencyMs / 3)
    $env:CHATAGENT_CAPACITY_TEST_MOCK_STREAM_TOTAL_MS = [string][int]($LatencyMs - ($LatencyMs / 3))
    $env:CHATAGENT_LOAD_CONCURRENT_USERS = [string]$Users
    $env:CHATAGENT_LOAD_HOLD_SECONDS = [string]$HoldSeconds
    $env:CHATAGENT_E2E_AWAIT_SECONDS = [string]([math]::Max(30, [math]::Ceiling($LatencyMs / 1000) + 20))
    $env:CHATAGENT_LOAD_USER_PREFIX = 'capacity-' + ([guid]::NewGuid().ToString('N').Substring(0, 12))
    $env:CHATAGENT_TURN_OUTCOME_PATH = Join-Path $runDirectory 'gatling-turn-outcomes.json'
    $env:CHATAGENT_TURN_SAMPLES_PATH = Join-Path $runDirectory 'turn-samples.json'
    $backend = $null
    $gatling = $null
    $failure = $null
    $resources = [System.Collections.Generic.List[object]]::new()
    $queueSamples = [System.Collections.Generic.List[object]]::new()
    $port = 8080
    try {
        $jar = Get-ChatAgentBackendJar $paths.MavenRoot
        $backend = Start-ChatAgentTestBackend -JarPath $jar -MavenRoot $paths.MavenRoot `
            -RunDirectory $runDirectory -Port $port -LogPrefix 'backend'
        @{ schemaVersion = 1; ownedPids = @($backend.Id); cleanupVerified = $false } |
            ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
        Wait-ChatAgentHealth $port
        if ((Get-ChatAgentRedisCardinality) -ne 0) { throw 'Redis permit set was not empty before capacity run.' }

        $gatlingArgs = @('-q', '-f', 'tools\gatling\pom.xml', 'gatling:test',
            '-Dgatling.simulationClass=com.yulong.chatagent.load.ChatTurnCapacitySimulation',
            "-DbaseUrl=http://localhost:$port", "-DconcurrentUsers=$Users", "-DholdSeconds=$HoldSeconds")
        $maven = Join-Path $paths.MavenRoot 'mvnw.cmd'
        $exitCodePath = Join-Path $runDirectory 'gatling-exit-code.txt'
        $quotedArguments = @($gatlingArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        $mavenCommand = "& '" + $maven.Replace("'", "''") + "' $quotedArguments; " +
            "`$code = `$LASTEXITCODE; Set-Content -LiteralPath '" + $exitCodePath.Replace("'", "''") +
            "' -Value `$code; exit `$code"
        $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($mavenCommand))
        $gatling = Start-Process -FilePath 'powershell.exe' `
            -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', $encodedCommand) `
            -WorkingDirectory $paths.MavenRoot -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $runDirectory 'gatling.out.log') `
            -RedirectStandardError (Join-Path $runDirectory 'gatling.err.log') -PassThru
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        while (-not $gatling.HasExited) {
            $backend.Refresh()
            $resources.Add([ordered]@{
                    elapsedMs = $watch.ElapsedMilliseconds
                    backendWorkingSetBytes = $backend.WorkingSet64
                    backendCpuTotalMs = [math]::Round($backend.TotalProcessorTime.TotalMilliseconds, 3)
                    availableMemoryBytes = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024
                })
            $queue = Get-QueueTotals
            $queueSamples.Add([ordered]@{ elapsedMs = $watch.ElapsedMilliseconds; ready = $queue.ready; unacknowledged = $queue.unacknowledged })
            Start-Sleep -Seconds 1
            $gatling.Refresh()
        }
        $gatling.WaitForExit()
        $gatling.Refresh()
        if (-not (Test-Path -LiteralPath $exitCodePath)) { throw 'Gatling child did not record an exit code.' }
        $gatlingExitCode = [int](Get-Content -Raw -LiteralPath $exitCodePath)
        Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ('gatlingExitCode=' + $gatlingExitCode)
        if ($gatlingExitCode -ne 0) { throw 'ChatTurnCapacitySimulation failed.' }

        $drained = $false
        foreach ($attempt in 1..60) {
            $queue = Get-QueueTotals
            if ($queue.ready -eq 0 -and $queue.unacknowledged -eq 0 -and
                (Get-ChatAgentRedisCardinality) -eq 0) { $drained = $true; break }
            Start-Sleep -Seconds 1
        }
        $outcomes = Get-Content -Raw -LiteralPath $env:CHATAGENT_TURN_OUTCOME_PATH | ConvertFrom-Json
        $samplesDoc = Get-Content -Raw -LiteralPath $env:CHATAGENT_TURN_SAMPLES_PATH | ConvertFrom-Json
        $samples = @($samplesDoc.samples | ForEach-Object { [long]$_ })
        $completion = if ([long]$outcomes.submitted -eq 0) { 0.0 } else { [double]$outcomes.successful / [double]$outcomes.submitted }
        $ko = Get-GatlingGlobalKoPercent (Join-Path $runDirectory 'gatling.out.log')
        $finalQueue = Get-QueueTotals
        $finalPermits = Get-ChatAgentRedisCardinality
        $p50 = Get-Percentile $samples 0.50
        $p95 = Get-Percentile $samples 0.95
        $p99 = Get-Percentile $samples 0.99
        @{ schemaVersion = 1; samples = @($queueSamples); finalReady = $finalQueue.ready; finalUnacknowledged = $finalQueue.unacknowledged } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'queue.json')
        @{ schemaVersion = 1; samples = @($resources); logicalProcessorCount = [Environment]::ProcessorCount; coLocatedLoadGenerator = $true } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'resources.json')
        @{ schemaVersion = 1; redis = [ordered]@{ finalPermitCardinality = $finalPermits }; fixture = @(); circuit = @(); latency = [ordered]@{ p50Ms = $p50; p95Ms = $p95; p99Ms = $p99 } } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'observations.json')
        $reportability = Get-CapacityReportability -Purpose $Purpose -P95Ms $p95 `
            -Drained:$drained -CompletionRatio $completion -GatlingKoPercent $ko `
            -FinalInFlight ([long]$outcomes.finalInFlight) `
            -InvalidSuccessAfterFailedCheck ([long]$outcomes.invalidSuccessAfterFailedCheck)
        @{ schemaVersion = 1; submitted = [long]$outcomes.submitted; successful = [long]$outcomes.successful; terminalFailed = [long]$outcomes.terminalFailed; timedOut = [long]$outcomes.timedOut; interrupted = [long]$outcomes.interrupted; finalInFlight = [long]$outcomes.finalInFlight; reconciled = [bool]$outcomes.reconciled; completionRatio = $completion; gatlingKoPercent = $ko; invalidSuccessAfterFailedCheck = [long]$outcomes.invalidSuccessAfterFailedCheck; reportable = [bool]$reportability.reportable; reason = [string]$reportability.reason } |
            ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
    } catch {
        $failure = $_
        Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ('failureType=' + $_.Exception.GetType().Name)
        Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ('failureId=' + $_.FullyQualifiedErrorId)
        Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ('failureLocation=' + $_.InvocationInfo.ScriptLineNumber)
    } finally {
        Stop-ChatAgentOwnedProcesses @($gatling, $backend)
        try { Remove-ChatAgentTestUsersByPrefix $env:CHATAGENT_LOAD_USER_PREFIX } catch { if (-not $failure) { $failure = $_ } }
        $remaining = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM t_user WHERE username LIKE '$($env:CHATAGENT_LOAD_USER_PREFIX)-%';"
        $cleanupVerified = -not [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) -and $remaining -eq 0
        @{ schemaVersion = 1; ownedPids = @(); cleanupVerified = $cleanupVerified } |
            ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
        $manifestPath = Join-Path $runDirectory 'manifest.json'
        $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
        $manifest.completedAt = (Get-Date).ToUniversalTime().ToString('o')
        $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath
        if ($failure) {
            @{ schemaVersion = 1; submitted = 0; successful = 0; terminalFailed = 0; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 1.0; gatlingKoPercent = 100.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = 'runner-failure' } |
                ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
        }
        & $settler -RunDirectory $runDirectory | Out-Null
        Remove-Item Env:CHATAGENT_TURN_OUTCOME_PATH,Env:CHATAGENT_TURN_SAMPLES_PATH,Env:CHATAGENT_LOAD_USER_PREFIX -ErrorAction SilentlyContinue
    }
    if ($failure) { throw $failure }
    $runDirectory
}

$runs = [System.Collections.Generic.List[object]]::new()
if ($Mode -in @('Calibration', 'All')) {
    foreach ($users in @(5, 10, 20, 40, 60)) { $runs.Add(@('calibration', 'B3', 1, 300, $users, 60)) }
}
if ($Mode -in @('Formal', 'All')) {
    foreach ($row in @('B0', 'B1', 'B2', 'B3')) { foreach ($repeat in 1..3) { $runs.Add(@('causal-ab', $row, $repeat, 300, 20, 300)) } }
}
if ($Mode -in @('Sensitivity', 'All')) {
    foreach ($latency in @(1200, 3000)) { foreach ($repeat in 1..3) { $runs.Add(@('sensitivity', 'B3', $repeat, $latency, 20, 300)) } }
}
if ($Mode -eq 'Single') {
    $purpose = switch ($SinglePurpose) {
        'Calibration' { 'calibration' }
        'Formal' { 'causal-ab' }
        'Sensitivity' { 'sensitivity' }
    }
    $hold = if ($purpose -eq 'calibration') { 60 } else { 300 }
    $runs.Add(@($purpose, $MatrixRow, $Repetition, $StubLatencyMs, $ConcurrentUsers, $hold))
}

foreach ($run in $runs) {
    Invoke-CapacityRun -Purpose $run[0] -RowName $run[1] -Repeat $run[2] `
        -LatencyMs $run[3] -Users $run[4] -HoldSeconds $run[5]
}
