param(
    [switch]$DryRun,
    [switch]$RedisUnavailable,
    [int]$Requests = 20,
    [int]$RequestsPerSecond = 1,
    [int]$BurstCapacity = 2
)

$ErrorActionPreference = 'Stop'
$common = Join-Path $PSScriptRoot 'new-run-artifacts.ps1'
if ($DryRun) {
    & $common -Scenario 'EntryRateLimitSimulation' -Profile 'capacity-test' `
        -EntryEnabled:$true -AgentRunEnabled:$false -AgentRunMax $null `
        -OverrideSource 'run-entry-rate-limit.ps1' -DryRun
    exit $LASTEXITCODE
}

. (Join-Path $PSScriptRoot 'limiter-run-support.ps1')
$paths = Get-ChatAgentPaths
Import-ChatAgentLocalEnvironment $paths.EnvironmentFile
$env:JAVA_HOME = 'C:\Users\guany\.jdks\ms-17.0.18'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SPRING_PROFILES_ACTIVE = 'capacity-test'
$env:CHATAGENT_RATE_LIMIT_ENTRY_ENABLED = 'true'
$env:CHATAGENT_RATE_LIMIT_ENTRY_REQUESTS_PER_SECOND = [string]$RequestsPerSecond
$env:CHATAGENT_RATE_LIMIT_ENTRY_BURST_CAPACITY = [string]$BurstCapacity
$env:CHATAGENT_RATE_LIMIT_AGENT_RUN_ENABLED = 'false'
$env:CHATAGENT_CAPACITY_TEST_MOCK_TTFT_MS = '50'
$env:CHATAGENT_CAPACITY_TEST_MOCK_STREAM_TOTAL_MS = '50'
$env:MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE = 'health,metrics'
$env:CHATAGENT_RATE_LIMIT_ENTRY_LOCAL_FALLBACK_MAX_IDENTITIES = '3'
$env:SPRING_DATA_REDIS_CONNECT_TIMEOUT = '250ms'
$env:SPRING_DATA_REDIS_TIMEOUT = '250ms'

$runDirectory = & $common -Scenario 'EntryRateLimitSimulation' -Profile 'capacity-test' `
    -EntryEnabled:$true -AgentRunEnabled:$false -AgentRunMax $null `
    -OverrideSource 'run-entry-rate-limit.ps1' | Select-Object -Last 1
$backend = $null
$identity = $null
$failure = $null
$redisStopped = $false
$port = 8080

try {
    $jar = Get-ChatAgentBackendJar $paths.MavenRoot
    $backend = Start-ChatAgentTestBackend -JarPath $jar -MavenRoot $paths.MavenRoot `
        -RunDirectory $runDirectory -Port $port -LogPrefix 'backend'
    @{ schemaVersion = 1; ownedPids = @($backend.Id); cleanupVerified = $false } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    Wait-ChatAgentHealth $port
    $identity = New-ChatAgentTestIdentity -Port $port -SessionCount $Requests -Prefix 'entry-limit-proof'
    $sessionLiterals = @($identity.SessionIds | ForEach-Object { "'$_'" }) -join ','
    $beforeMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals);"
    $beforeOutbox = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionLiterals);"

    $scenarioResult = Join-Path $runDirectory 'entry-scenario.json'
    $sessionFeeder = Join-Path $runDirectory 'entry-sessions.csv'
    @('sessionId') + @($identity.SessionIds) | Set-Content -LiteralPath $sessionFeeder
    $env:CHATAGENT_LOAD_ACCESS_TOKEN = $identity.AccessToken
    $env:CHATAGENT_SESSION_FEEDER = $sessionFeeder
    $env:CHATAGENT_SCENARIO_RESULT_PATH = $scenarioResult
    if ($RedisUnavailable) {
        $env:CHATAGENT_ENTRY_EXPECT_DOWNSTREAM_FAILURE = 'true'
        & docker stop chatagent-redis | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to stop Redis for entry fallback proof.' }
        $redisStopped = $true
    }
    Push-Location $paths.MavenRoot
    try {
        & .\mvnw.cmd -q -f tools\gatling\pom.xml gatling:test `
            '-Dgatling.simulationClass=com.yulong.chatagent.load.EntryRateLimitSimulation' `
            "-DbaseUrl=http://localhost:$port" `
            "-DentryRequests=$Requests" *>&1 |
            Tee-Object -LiteralPath (Join-Path $runDirectory 'gatling.log')
        if ($LASTEXITCODE -ne 0) { throw 'EntryRateLimitSimulation failed.' }
    } finally {
        Pop-Location
        Remove-Item Env:CHATAGENT_LOAD_ACCESS_TOKEN -ErrorAction SilentlyContinue
        Remove-Item Env:CHATAGENT_SESSION_FEEDER -ErrorAction SilentlyContinue
        Remove-Item Env:CHATAGENT_SCENARIO_RESULT_PATH -ErrorAction SilentlyContinue
    }

    $scenario = Get-Content -Raw -LiteralPath $scenarioResult | ConvertFrom-Json
    Start-Sleep -Seconds 1
    $afterMessages = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM chat_message WHERE session_id IN ($sessionLiterals) AND role = 'user';"
    $afterOutbox = Invoke-ChatAgentSqlScalar "SELECT COUNT(*) FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionLiterals);"
    $redisFailures = if ($RedisUnavailable) {
        Get-ChatAgentMetricValue -Port $port -MetricName 'chatagent.rate_limit.entry.redis.failures'
    } else { 0.0 }
    $fallbackAllowed = if ($RedisUnavailable) {
        Get-ChatAgentMetricValue -Port $port -MetricName 'chatagent.rate_limit.entry.requests' `
            -Tag @('outcome:fallback', 'policy:local_bucket')
    } else { 0.0 }
    $fallbackCacheSize = if ($RedisUnavailable) {
        Get-ChatAgentMetricValue -Port $port -MetricName 'chatagent.rate_limit.entry.local_cache.size'
    } else { 0.0 }
    @{ schemaVersion = 1; entry = [ordered]@{ redisUnavailable = [bool]$RedisUnavailable; requestsPerSecond = $RequestsPerSecond; burstCapacity = $BurstCapacity; submitted = $scenario.submitted; allowed = $scenario.allowed; admittedDownstreamFailed = $scenario.admittedDownstreamFailed; rejected = $scenario.rejected; unexpected = $scenario.unexpected; redisFailures = $redisFailures; localBucketAllowed = $fallbackAllowed; localFallbackCacheSize = $fallbackCacheSize; localFallbackMaxIdentities = 3; beforeUserMessages = $beforeMessages; afterUserMessages = $afterMessages; beforeOutbox = $beforeOutbox; afterOutbox = $afterOutbox } } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'observations.json')
    if ($beforeMessages -ne 0L -or $beforeOutbox -ne 0L -or
        (-not $RedisUnavailable -and $afterMessages -ne [long]$scenario.allowed) -or
        (-not $RedisUnavailable -and $afterOutbox -ne [long]$scenario.allowed) -or
        ($RedisUnavailable -and ([long]$scenario.admittedDownstreamFailed -ne $BurstCapacity -or
            $afterMessages -ne 0L -or $afterOutbox -ne 0L)) -or
        [long]$scenario.rejected -le 0L -or
        [long]$scenario.unexpected -ne 0L -or
        ($RedisUnavailable -and ($redisFailures -lt $Requests -or
            $fallbackAllowed -ne $BurstCapacity -or $fallbackCacheSize -gt 3.0))) {
        throw 'Entry limiter side-effect or envelope audit failed.'
    }

    $queue = Get-ChatAgentQueueSnapshot
    @{ schemaVersion = 1; samples = @($queue); finalReady = ($queue | Measure-Object messages_ready -Sum).Sum; finalUnacknowledged = ($queue | Measure-Object messages_unacknowledged -Sum).Sum } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'queue.json')
    @{ schemaVersion = 1; entry = [ordered]@{ redisUnavailable = [bool]$RedisUnavailable; requestsPerSecond = $RequestsPerSecond; burstCapacity = $BurstCapacity; submitted = $scenario.submitted; allowed = $scenario.allowed; admittedDownstreamFailed = $scenario.admittedDownstreamFailed; rejected = $scenario.rejected; redisFailures = $redisFailures; localBucketAllowed = $fallbackAllowed; localFallbackCacheSize = $fallbackCacheSize; localFallbackMaxIdentities = 3; beforeUserMessages = $beforeMessages; afterUserMessages = $afterMessages; beforeOutbox = $beforeOutbox; afterOutbox = $afterOutbox } } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'observations.json')
    @{ schemaVersion = 1; samples = @([ordered]@{ pid = $backend.Id; workingSetBytes = $backend.WorkingSet64; sampledAt = (Get-Date).ToUniversalTime().ToString('o') }) } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'resources.json')
    @{ schemaVersion = 1; samples = @() } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'turn-samples.json')
    $terminalFailed = [long]$scenario.rejected + [long]$scenario.admittedDownstreamFailed
    $completionRatio = if ([long]$scenario.submitted -eq 0) { 0.0 } else {
        [double]$scenario.allowed / [double]$scenario.submitted
    }
    @{ schemaVersion = 1; submitted = [long]$scenario.submitted; successful = [long]$scenario.allowed; terminalFailed = $terminalFailed; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = $completionRatio; gatlingKoPercent = 0.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = if ($RedisUnavailable) { 'entry-redis-fallback-audit' } else { 'entry-envelope-audit' } } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
} catch {
    $failure = $_
    Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ("failureType=" + $_.Exception.GetType().Name)
} finally {
    if ($redisStopped) {
        & docker start chatagent-redis | Out-Null
        if ($LASTEXITCODE -ne 0 -and -not $failure) {
            $failure = [System.InvalidOperationException]::new('Failed to restart Redis after entry fallback proof.')
        }
        $redisStopped = $false
        Start-Sleep -Seconds 2
    }
    Remove-Item Env:CHATAGENT_ENTRY_EXPECT_DOWNSTREAM_FAILURE -ErrorAction SilentlyContinue
    try { Remove-ChatAgentTestIdentity -Port $port -Identity $identity } catch { if (-not $failure) { $failure = $_ } }
    Stop-ChatAgentOwnedProcesses @($backend)
    @{ schemaVersion = 1; ownedPids = @(); cleanupVerified = -not [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    $manifestPath = Join-Path $runDirectory 'manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
    if ($failure) {
        @{ schemaVersion = 1; submitted = $Requests; successful = 0; terminalFailed = $Requests; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 0.0; gatlingKoPercent = 100.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = 'runner-failure' } |
            ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
    }
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $runDirectory | Out-Null
}

if ($failure) { throw $failure }
Write-Output $runDirectory
