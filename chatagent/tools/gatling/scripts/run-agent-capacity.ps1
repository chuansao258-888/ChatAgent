param(
    [switch]$DryRun,
    [ValidateSet(1, 2)][int]$BackendInstances = 1,
    [int]$Turns = 8,
    [int]$MaxConcurrency = 3,
    [int]$PermitTtlMs = 1500,
    [int]$PermitRenewIntervalMs = 400,
    [int]$StubTtftMs = 2500,
    [int]$StubStreamMs = 500
)

$ErrorActionPreference = 'Stop'
$common = Join-Path $PSScriptRoot 'new-run-artifacts.ps1'
if ($DryRun) {
    & $common -Scenario 'AgentCapacitySimulation' -Profile 'capacity-test' `
        -EntryEnabled:$false -AgentRunEnabled:$true -AgentRunMax 3 `
        -OverrideSource 'run-agent-capacity.ps1' -DryRun
    exit $LASTEXITCODE
}
if ($MaxConcurrency -ne 3) {
    throw 'AgentCapacitySimulation requires max-concurrency=3 by the authoritative matrix.'
}

. (Join-Path $PSScriptRoot 'limiter-run-support.ps1')
$paths = Get-ChatAgentPaths
Import-ChatAgentLocalEnvironment $paths.EnvironmentFile
$env:JAVA_HOME = 'C:\Users\guany\.jdks\ms-17.0.18'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SPRING_PROFILES_ACTIVE = 'capacity-test'
$env:CHATAGENT_RATE_LIMIT_ENTRY_ENABLED = 'false'
$env:CHATAGENT_RATE_LIMIT_AGENT_RUN_ENABLED = 'true'
$env:CHATAGENT_RATE_LIMIT_AGENT_RUN_MAX_CONCURRENCY = '3'
$env:CHATAGENT_RATE_LIMIT_AGENT_RUN_PERMIT_TTL_MS = [string]$PermitTtlMs
$env:CHATAGENT_RATE_LIMIT_AGENT_RUN_PERMIT_RENEW_INTERVAL_MS = [string]$PermitRenewIntervalMs
$env:CHATAGENT_RATE_LIMIT_AGENT_RUN_WAIT_TIMEOUT_MS = '60000'
$env:CHATAGENT_CAPACITY_TEST_MOCK_TTFT_MS = [string]$StubTtftMs
$env:CHATAGENT_CAPACITY_TEST_MOCK_STREAM_TOTAL_MS = [string]$StubStreamMs
$env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,metrics'

$runDirectory = & $common -Scenario 'AgentCapacitySimulation' -Profile 'capacity-test' `
    -EntryEnabled:$false -AgentRunEnabled:$true -AgentRunMax 3 `
    -OverrideSource 'run-agent-capacity.ps1' | Select-Object -Last 1
$backends = @()
$gatling = $null
$identity = $null
$failure = $null
$ports = @(8080, 8081)[0..($BackendInstances - 1)]
$redisSamples = [System.Collections.Generic.List[object]]::new()
$queueSamples = [System.Collections.Generic.List[object]]::new()
$resourceSamples = [System.Collections.Generic.List[object]]::new()

try {
    if ((Get-ChatAgentRedisCardinality) -ne 0) {
        throw 'Redis active permit set is not empty before the run.'
    }
    $jar = Get-ChatAgentBackendJar $paths.MavenRoot
    foreach ($index in 0..($BackendInstances - 1)) {
        $backend = Start-ChatAgentTestBackend -JarPath $jar -MavenRoot $paths.MavenRoot `
            -RunDirectory $runDirectory -Port $ports[$index] -LogPrefix "backend-$($index + 1)"
        $backends += $backend
    }
    @{ schemaVersion = 1; ownedPids = @($backends.Id); cleanupVerified = $false } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    foreach ($port in $ports) { Wait-ChatAgentHealth $port }

    $identity = New-ChatAgentTestIdentity -Port $ports[0] -SessionCount $Turns -Prefix 'agent-capacity-proof'
    $sessionLiterals = @($identity.SessionIds | ForEach-Object { "'$_'" }) -join ','
    $beforeMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals);"
    $beforeOutbox = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionLiterals);"
    $sessionFeeder = Join-Path $runDirectory 'agent-sessions.csv'
    @('sessionId') + @($identity.SessionIds) | Set-Content -LiteralPath $sessionFeeder
    $scenarioResult = Join-Path $runDirectory 'agent-scenario.json'
    $env:CHATAGENT_LOAD_ACCESS_TOKEN = $identity.AccessToken
    $env:CHATAGENT_SESSION_FEEDER = $sessionFeeder
    $env:CHATAGENT_SCENARIO_RESULT_PATH = $scenarioResult

    $gatlingArgs = @(
        '-q', '-f', 'tools\gatling\pom.xml', 'gatling:test',
        '-Dgatling.simulationClass=com.yulong.chatagent.load.AgentCapacitySimulation',
        "-DbaseUrl=http://localhost:$($ports[0])",
        "-DagentTurns=$Turns",
        '-De2eAwaitSeconds=60'
    )
    $gatling = Start-Process -FilePath (Join-Path $paths.MavenRoot 'mvnw.cmd') `
        -ArgumentList $gatlingArgs -WorkingDirectory $paths.MavenRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDirectory 'gatling.out.log') `
        -RedirectStandardError (Join-Path $runDirectory 'gatling.err.log') -PassThru

    $started = [System.Diagnostics.Stopwatch]::StartNew()
    $iteration = 0
    while (-not $gatling.HasExited) {
        $iteration++
        $redisSamples.Add([ordered]@{
                elapsedMs = $started.ElapsedMilliseconds
                active = Get-ChatAgentRedisCardinality
            })
        if ($iteration % 10 -eq 1) {
            $queues = Get-ChatAgentQueueSnapshot
            $queueSamples.Add([ordered]@{
                    elapsedMs = $started.ElapsedMilliseconds
                    ready = ($queues | Measure-Object messages_ready -Sum).Sum
                    unacknowledged = ($queues | Measure-Object messages_unacknowledged -Sum).Sum
                })
            foreach ($backend in $backends) {
                $backend.Refresh()
                $resourceSamples.Add([ordered]@{
                        elapsedMs = $started.ElapsedMilliseconds
                        pid = $backend.Id
                        workingSetBytes = $backend.WorkingSet64
                    })
            }
        }
        Start-Sleep -Milliseconds 100
        $gatling.Refresh()
    }
    if ($gatling.ExitCode -ne 0) { throw 'AgentCapacitySimulation failed.' }
    Remove-Item Env:CHATAGENT_LOAD_ACCESS_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:CHATAGENT_SESSION_FEEDER -ErrorAction SilentlyContinue
    Remove-Item Env:CHATAGENT_SCENARIO_RESULT_PATH -ErrorAction SilentlyContinue

    $scenario = Get-Content -Raw -LiteralPath $scenarioResult | ConvertFrom-Json
    $finalCardinality = -1
    $finalQueues = @()
    foreach ($attempt in 1..30) {
        $finalCardinality = Get-ChatAgentRedisCardinality
        $finalQueues = Get-ChatAgentQueueSnapshot
        $ready = ($finalQueues | Measure-Object messages_ready -Sum).Sum
        $unacknowledged = ($finalQueues | Measure-Object messages_unacknowledged -Sum).Sum
        if ($finalCardinality -eq 0 -and $ready -eq 0 -and $unacknowledged -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    $maxCardinality = ($redisSamples | ForEach-Object { [int]$_['active'] } |
        Measure-Object -Maximum).Maximum
    $renewalBeyondTtl = @($redisSamples | Where-Object {
            $_.elapsedMs -gt $PermitTtlMs -and $_.active -gt 0
        }).Count -gt 0
    $waitRequeues = 0.0
    $failureRetries = 0
    foreach ($index in 0..($BackendInstances - 1)) {
        $logPath = Join-Path $runDirectory "backend-$($index + 1).out.log"
        $waitRequeues += Get-ChatAgentMetricValue -Port $ports[$index] `
            -MetricName 'chatagent.agent_run.capacity.waits' -Tag 'outcome:requeued'
        $failureRetries += @(Select-String -LiteralPath $logPath -Pattern 'MQ task moved to retry queue').Count
    }
    $afterUserMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals) AND role = 'user';"
    $afterAssistantMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals) AND role = 'assistant';"
    $afterOutbox = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionLiterals);"
    @{ schemaVersion = 1; samples = @($queueSamples); finalReady = ($finalQueues | Measure-Object messages_ready -Sum).Sum; finalUnacknowledged = ($finalQueues | Measure-Object messages_unacknowledged -Sum).Sum } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'queue.json')
    @{ schemaVersion = 1; samples = @($resourceSamples) } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'resources.json')
    @{ schemaVersion = 1; redis = [ordered]@{ samples = @($redisSamples); maxActive = $maxCardinality; finalActive = $finalCardinality; renewalBeyondOriginalTtl = $renewalBeyondTtl }; agent = [ordered]@{ backendInstances = $BackendInstances; turns = $Turns; maxConcurrency = 3; permitTtlMs = $PermitTtlMs; renewIntervalMs = $PermitRenewIntervalMs; waitRequeues = $waitRequeues; failureRetries = $failureRetries; beforeUserMessages = $beforeMessages; afterUserMessages = $afterUserMessages; afterAssistantMessages = $afterAssistantMessages; beforeOutbox = $beforeOutbox; afterOutbox = $afterOutbox } } |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runDirectory 'observations.json')
    if ($beforeMessages -ne 0L -or $beforeOutbox -ne 0L -or
        $maxCardinality -ne 3 -or $finalCardinality -ne 0 -or -not $renewalBeyondTtl -or
        $waitRequeues -le 0 -or $failureRetries -ne 0 -or
        $afterUserMessages -ne $Turns -or $afterAssistantMessages -ne $Turns -or
        [long]$scenario.successful -ne $Turns) {
        throw 'Agent capacity envelope, renewal, WAIT, or side-effect audit failed.'
    }

    @{ schemaVersion = 1; samples = @() } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'turn-samples.json')
    @{ schemaVersion = 1; submitted = [long]$scenario.submitted; successful = [long]$scenario.successful; terminalFailed = [long]$scenario.terminalFailed; timedOut = [long]$scenario.timedOut; interrupted = [long]$scenario.interrupted; finalInFlight = [long]$scenario.finalInFlight; reconciled = [bool]$scenario.reconciled; completionRatio = [double]$scenario.successful / [double]$scenario.submitted; gatlingKoPercent = 0.0; invalidSuccessAfterFailedCheck = [long]$scenario.invalidSuccessAfterFailedCheck; reportable = $true; reason = 'agent-capacity-proved' } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
    $manifestPath = Join-Path $runDirectory 'manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.effectiveConfiguration | Add-Member -NotePropertyName backendInstances -NotePropertyValue $BackendInstances
    $manifest.effectiveConfiguration | Add-Member -NotePropertyName permitTtlMs -NotePropertyValue $PermitTtlMs
    $manifest.effectiveConfiguration | Add-Member -NotePropertyName permitRenewIntervalMs -NotePropertyValue $PermitRenewIntervalMs
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
} catch {
    $failure = $_
    Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ("failureType=" + $_.Exception.GetType().Name)
} finally {
    Remove-Item Env:CHATAGENT_LOAD_ACCESS_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:CHATAGENT_SESSION_FEEDER -ErrorAction SilentlyContinue
    Remove-Item Env:CHATAGENT_SCENARIO_RESULT_PATH -ErrorAction SilentlyContinue
    try { Remove-ChatAgentTestIdentity -Port $ports[0] -Identity $identity } catch { if (-not $failure) { $failure = $_ } }
    $ownedProcesses = @($gatling) + @($backends)
    Stop-ChatAgentOwnedProcesses $ownedProcesses
    $listenersRemain = @($ports | Where-Object {
            Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue
        }).Count -gt 0
    @{ schemaVersion = 1; ownedPids = @(); cleanupVerified = -not $listenersRemain } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    $manifestPath = Join-Path $runDirectory 'manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
    if ($failure) {
        @{ schemaVersion = 1; submitted = $Turns; successful = 0; terminalFailed = $Turns; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 0.0; gatlingKoPercent = 100.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = 'runner-failure' } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
    }
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $runDirectory | Out-Null
}

if ($failure) { throw $failure }
Write-Output $runDirectory
