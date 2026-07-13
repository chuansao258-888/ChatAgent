param(
    [switch]$DryRun,
    [string]$FixtureBaseUrl = 'http://127.0.0.1:8890'
)

$ErrorActionPreference = 'Stop'
$common = Join-Path $PSScriptRoot 'new-run-artifacts.ps1'
if ($DryRun) {
    & $common -Scenario 'RoutingResilienceSimulation' -Profile 'resilience-test' `
        -EntryEnabled:$false -AgentRunEnabled:$false -AgentRunMax $null `
        -OverrideSource 'run-routing-resilience.ps1' -FixtureBaseUrl $FixtureBaseUrl `
        -FailureThreshold 4 -OpenDurationMs 5000 -HalfOpenFlightTimeoutMs 5000 `
        -AgentMqDispatcherEnabled:$false -DryRun
    exit $LASTEXITCODE
}

. (Join-Path $PSScriptRoot 'limiter-run-support.ps1')
$paths = Get-ChatAgentPaths
Import-ChatAgentLocalEnvironment $paths.EnvironmentFile
$env:JAVA_HOME = 'C:\Users\guany\.jdks\ms-17.0.18'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SPRING_PROFILES_ACTIVE = 'resilience-test'
$env:CHATAGENT_RESILIENCE_FIXTURE_BASE_URL = $FixtureBaseUrl
$env:CHAT_ROUTING_HEALTH_FAILURE_THRESHOLD = '4'
$env:CHAT_ROUTING_HEALTH_OPEN_DURATION_MS = '5000'
$env:CHAT_ROUTING_HEALTH_HALF_OPEN_FLIGHT_TIMEOUT_MS = '5000'
$env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,metrics'
$env:CHATAGENT_MQ_DISPATCHERS_AGENT_RUN_ENABLED = 'false'

$runDirectory = & $common -Scenario 'RoutingResilienceSimulation' -Profile 'resilience-test' `
    -EntryEnabled:$false -AgentRunEnabled:$false -AgentRunMax $null `
    -OverrideSource 'run-routing-resilience.ps1' -FixtureBaseUrl $FixtureBaseUrl `
    -FailureThreshold 4 -OpenDurationMs 5000 -HalfOpenFlightTimeoutMs 5000 `
    -AgentMqDispatcherEnabled:$false |
    Select-Object -Last 1
$fixture = $null
$backend = $null
$gatling = $null
$identity = $null
$failure = $null
$port = 8080
$resources = [System.Collections.Generic.List[object]]::new()

function Get-NearestRankPercentile([long[]]$Values, [double]$Percentile) {
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Max(0, [math]::Ceiling($Percentile * $sorted.Count) - 1)
    [long]$sorted[$index]
}

try {
    $fixtureUri = [Uri]$FixtureBaseUrl
    $env:PLAYWRIGHT_ROUTING_FIXTURE_PORT = [string]$fixtureUri.Port
    $fixtureScript = Join-Path $paths.WorkspaceRoot 'ui\e2e\fixtures\routing-provider-fixture.mjs'
    $fixtureArgument = '"' + $fixtureScript + '"'
    $fixture = Start-Process -FilePath 'node.exe' -ArgumentList $fixtureArgument `
        -WorkingDirectory $paths.WorkspaceRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDirectory 'fixture.out.log') `
        -RedirectStandardError (Join-Path $runDirectory 'fixture.err.log') -PassThru
    foreach ($attempt in 1..40) {
        try {
            Invoke-RestMethod -Uri "$FixtureBaseUrl/__routing-fixture/health" -TimeoutSec 1 | Out-Null
            break
        } catch {
            if ($attempt -eq 40) { throw 'Routing fixture did not become healthy.' }
            Start-Sleep -Milliseconds 250
        }
    }
    Invoke-RestMethod -Uri "$FixtureBaseUrl/__routing-fixture/reset" -Method Post | Out-Null

    $jar = Get-ChatAgentBackendJar $paths.MavenRoot
    $backend = Start-ChatAgentTestBackend -JarPath $jar -MavenRoot $paths.MavenRoot `
        -RunDirectory $runDirectory -Port $port -LogPrefix 'backend'
    @{ schemaVersion = 1; ownedPids = @($fixture.Id, $backend.Id); cleanupVerified = $false } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    Wait-ChatAgentHealth $port

    $identity = New-ChatAgentTestIdentity -Port $port -SessionCount 16 -Prefix 'routing-resilience-proof'
    $sessionLiterals = @($identity.SessionIds | ForEach-Object { "'$_'" }) -join ','
    $scenarioResult = Join-Path $runDirectory 'routing-scenario.json'
    $env:CHATAGENT_LOAD_ACCESS_TOKEN = $identity.AccessToken
    $env:CHATAGENT_SESSION_IDS = @($identity.SessionIds) -join ','
    $env:CHATAGENT_SCENARIO_RESULT_PATH = $scenarioResult

    $gatlingArgs = @('-q', '-f', 'tools\gatling\pom.xml', 'gatling:test',
        '-Dgatling.simulationClass=com.yulong.chatagent.load.RoutingResilienceSimulation',
        "-DbaseUrl=http://localhost:$port")
    $gatling = Start-Process -FilePath (Join-Path $paths.MavenRoot 'mvnw.cmd') `
        -ArgumentList $gatlingArgs -WorkingDirectory $paths.MavenRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDirectory 'gatling.out.log') `
        -RedirectStandardError (Join-Path $runDirectory 'gatling.err.log') -PassThru
    $started = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not $gatling.HasExited) {
        foreach ($process in @($backend, $fixture)) {
            $process.Refresh()
            $resources.Add([ordered]@{ elapsedMs = $started.ElapsedMilliseconds; pid = $process.Id; workingSetBytes = $process.WorkingSet64 })
        }
        Start-Sleep -Milliseconds 500
        $gatling.Refresh()
    }
    if ($gatling.ExitCode -ne 0) { throw 'RoutingResilienceSimulation failed.' }

    $scenario = Get-Content -Raw -LiteralPath $scenarioResult | ConvertFrom-Json
    $fixtureState = Invoke-RestMethod -Uri "$FixtureBaseUrl/__routing-fixture/state"
    $primaryCalls = @($fixtureState.calls | Where-Object provider -eq 'anthropic')
    $primaryScenarioCalls = @($primaryCalls | Where-Object scenario -ne 'internal')
    $fallbackScenarioCalls = @($fixtureState.calls | Where-Object {
            $_.provider -eq 'deepseek' -and $_.scenario -ne 'internal'
        })
    $openSkipPrimaryCalls = @($primaryScenarioCalls | Where-Object code -like 'BIRCH-SKIP*').Count
    $lateCalls = @($primaryScenarioCalls | Where-Object scenario -eq 'late').Count
    $errorCalls = @($primaryScenarioCalls | Where-Object scenario -eq 'error').Count
    $successCalls = @($primaryScenarioCalls | Where-Object scenario -in @('success', 'slow-success')).Count
    $opened = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.events' @('model:glm-5.2', 'event:opened')
    $halfOpen = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.events' @('model:glm-5.2', 'event:half_open')
    $probeAdmitted = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.events' @('model:glm-5.2', 'event:half_open_probe_admitted')
    $closed = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.events' @('model:glm-5.2', 'event:closed')
    $reopened = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.events' @('model:glm-5.2', 'event:reopened')
    $deniedOpen = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.decisions' @('model:glm-5.2', 'decision:denied_open')
    $deniedHalfOpen = Get-ChatAgentMetricValue $port 'chatagent.llm.circuit.decisions' @('model:glm-5.2', 'decision:denied_half_open')
    $userMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals) AND role='user';"
    $assistantMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals) AND role='assistant';"
    $lateLeaks = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals) AND content LIKE '%PRIMARY-LATE-LEAK%';"
    $backendLog = Get-Content -LiteralPath (Join-Path $runDirectory 'backend.out.log')
    $firstPacketLatencies = [System.Collections.Generic.List[long]]::new()
    $failoverLatencies = [System.Collections.Generic.List[long]]::new()
    $pendingPrimaryFailureByThread = @{}
    $fallbackStarts = 0
    $fallbackSuccesses = 0
    $activeHalfOpenProbes = 0
    $maxConcurrentHalfOpenProbes = 0
    foreach ($line in $backendLog) {
        if ($line -match 'Model circuit entering HALF_OPEN: modelId=glm-5\.2') {
            $activeHalfOpenProbes++
            $maxConcurrentHalfOpenProbes = [math]::Max(
                $maxConcurrentHalfOpenProbes, $activeHalfOpenProbes)
        }
        if ($line -match 'Model circuit (?:CLOSED|reopened after HALF_OPEN failure): modelId=glm-5\.2') {
            $activeHalfOpenProbes--
        }
        if ($line -match 'first-packet probe (?:succeeded|failed):.*latencyMs=(\d+)') {
            $firstPacketLatencies.Add([long]$Matches[1])
        }
        if ($line -match '\[(?<thread>[^\]]+)\].*first-packet probe failed: model=glm-5\.2.*latencyMs=(?<latency>\d+)') {
            $pendingPrimaryFailureByThread[$Matches.thread.Trim()] = [long]$Matches.latency
        }
        if ($line -match 'first-packet probe started: model=deepseek-v4-flash') {
            $fallbackStarts++
        }
        if ($line -match '\[(?<thread>[^\]]+)\].*first-packet probe succeeded: model=deepseek-v4-flash.*latencyMs=(?<latency>\d+)') {
            $fallbackSuccesses++
            $thread = $Matches.thread.Trim()
            if ($pendingPrimaryFailureByThread.ContainsKey($thread)) {
                $failoverLatencies.Add([long]$pendingPrimaryFailureByThread[$thread] + [long]$Matches.latency)
                $pendingPrimaryFailureByThread.Remove($thread)
            }
        }
    }
    $fallbackSuccessRatio = if ($fallbackStarts -eq 0) { 0.0 } else {
        [double]$fallbackSuccesses / [double]$fallbackStarts
    }
    $latency = [ordered]@{
        firstPacketMs = [ordered]@{ p50 = Get-NearestRankPercentile $firstPacketLatencies 0.50; p95 = Get-NearestRankPercentile $firstPacketLatencies 0.95; p99 = Get-NearestRankPercentile $firstPacketLatencies 0.99 }
        failoverMs = [ordered]@{ p50 = Get-NearestRankPercentile $failoverLatencies 0.50; p95 = Get-NearestRankPercentile $failoverLatencies 0.95; p99 = Get-NearestRankPercentile $failoverLatencies 0.99 }
    }
    @{ schemaVersion = 1; fixture = [ordered]@{ primaryScenarioCalls = $primaryScenarioCalls; fallbackScenarioCallCount = $fallbackScenarioCalls.Count; openSkipPrimaryCalls = $openSkipPrimaryCalls; lateCalls = $lateCalls; errorCalls = $errorCalls; successCalls = $successCalls }; circuit = [ordered]@{ opened = $opened; halfOpen = $halfOpen; probeAdmitted = $probeAdmitted; closed = $closed; reopened = $reopened; deniedOpen = $deniedOpen; deniedHalfOpen = $deniedHalfOpen; maxConcurrentHalfOpenProbes = $maxConcurrentHalfOpenProbes; finalActiveHalfOpenProbes = $activeHalfOpenProbes }; routing = [ordered]@{ primaryCallCount = $primaryScenarioCalls.Count; fallbackCallCount = $fallbackScenarioCalls.Count; fallbackStarts = $fallbackStarts; fallbackSuccesses = $fallbackSuccesses; fallbackSuccessRatio = $fallbackSuccessRatio; latency = $latency }; messages = [ordered]@{ user = $userMessages; assistant = $assistantMessages; lateLeaks = $lateLeaks }; scenario = $scenario } |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runDirectory 'observations.json')
    if ([long]$scenario.successful -ne 16 -or $openSkipPrimaryCalls -ne 0 -or
        $lateCalls -lt 1 -or $errorCalls -lt 7 -or $successCalls -lt 1 -or
        $opened -ne 2 -or $halfOpen -ne 2 -or $probeAdmitted -ne 0 -or
        $closed -ne 1 -or $reopened -ne 1 -or $deniedOpen -lt 1 -or
        $fallbackStarts -le 0 -or $fallbackSuccessRatio -ne 1.0 -or
        $failoverLatencies.Count -le 0 -or
        $maxConcurrentHalfOpenProbes -ne 1 -or $activeHalfOpenProbes -ne 0 -or
        $userMessages -ne 16 -or $assistantMessages -ne 16 -or $lateLeaks -ne 0) {
        throw 'Routing circuit, fixture, fallback, or leakage evidence did not reconcile.'
    }
    @{ schemaVersion = 1; samples = @($resources) } | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'resources.json')
    @{ schemaVersion = 1; submitted = 16; successful = 16; terminalFailed = 0; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 1.0; gatlingKoPercent = 0.0; invalidSuccessAfterFailedCheck = 0; reportable = $true; reason = 'routing-resilience-proved' } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
} catch {
    $failure = $_
    Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ("failureType=" + $_.Exception.GetType().Name)
} finally {
    Remove-Item Env:CHATAGENT_LOAD_ACCESS_TOKEN,Env:CHATAGENT_SESSION_IDS,Env:CHATAGENT_SCENARIO_RESULT_PATH -ErrorAction SilentlyContinue
    try { Remove-ChatAgentTestIdentity -Port $port -Identity $identity } catch { if (-not $failure) { $failure = $_ } }
    Stop-ChatAgentOwnedProcesses @($gatling, $backend, $fixture)
    $identityRemains = if ($identity) {
        (Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM t_user WHERE username = '$($identity.Username)';") -ne 0
    } else { $false }
    $cleanupVerified = -not [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) -and -not $identityRemains
    if (-not $cleanupVerified -and -not $failure) {
        $failure = [System.InvalidOperationException]::new('Routing runner cleanup verification failed.')
    }
    @{ schemaVersion = 1; ownedPids = @(); cleanupVerified = $cleanupVerified } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    $manifestPath = Join-Path $runDirectory 'manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
    if ($failure) {
        @{ schemaVersion = 1; submitted = 16; successful = 0; terminalFailed = 16; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 0.0; gatlingKoPercent = 100.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = 'runner-failure' } |
            ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
    }
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $runDirectory | Out-Null
}

if ($failure) { throw $failure }
Write-Output $runDirectory
