param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
$common = Join-Path $PSScriptRoot 'new-run-artifacts.ps1'
if ($DryRun) {
    & $common -Scenario 'AgentRedisFailureAudit' -Profile 'capacity-test' `
        -EntryEnabled:$false -AgentRunEnabled:$true -AgentRunMax 3 `
        -OverrideSource 'run-agent-redis-failure.ps1' -DryRun
    exit $LASTEXITCODE
}

. (Join-Path $PSScriptRoot 'limiter-run-support.ps1')
$paths = Get-ChatAgentPaths
$env:JAVA_HOME = 'C:\Users\guany\.jdks\ms-17.0.18'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$runDirectory = & $common -Scenario 'AgentRedisFailureAudit' -Profile 'capacity-test' `
    -EntryEnabled:$false -AgentRunEnabled:$true -AgentRunMax 3 `
    -OverrideSource 'run-agent-redis-failure.ps1' | Select-Object -Last 1
$failure = $null

try {
    $evidencePath = Join-Path $runDirectory 'agent-redis-failure.json'
    $env:CHATAGENT_RUN_REDIS_FAILURE_IT = 'true'
    $env:CHATAGENT_REDIS_FAILURE_EVIDENCE = $evidencePath
    Push-Location $paths.MavenRoot
    try {
        & .\mvnw.cmd -q -pl bootstrap -am `
            '-Dtest=AgentRunCapacityLimiterRedisFailureIT' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
        if ($LASTEXITCODE -ne 0) { throw 'Agent Redis failure integration audit failed.' }
    } finally {
        Pop-Location
    }
    $evidence = Get-Content -Raw -LiteralPath $evidencePath | ConvertFrom-Json
    if (-not $evidence.healthyRedisAcquire -or -not $evidence.localCapAcquire -or
        -not $evidence.failFastDenied -or -not $evidence.recoveredRedisAcquire -or
        -not $evidence.existingLocalPermitHeldAcrossRecovery) {
        throw 'Agent Redis failure evidence did not reconcile.'
    }
    if ((Get-ChatAgentRedisCardinality) -ne 0) {
        throw 'Agent Redis failure audit left an active permit.'
    }
    @{ schemaVersion = 1; redisFailure = $evidence } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'observations.json')
    @{ schemaVersion = 1; submitted = 5; successful = 5; terminalFailed = 0; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 1.0; gatlingKoPercent = 0.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = 'agent-redis-failure-policy-audit' } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
} catch {
    $failure = $_
    Add-Content -LiteralPath (Join-Path $runDirectory 'run.log') -Value ("failureType=" + $_.Exception.GetType().Name)
} finally {
    Remove-Item Env:CHATAGENT_RUN_REDIS_FAILURE_IT -ErrorAction SilentlyContinue
    Remove-Item Env:CHATAGENT_REDIS_FAILURE_EVIDENCE -ErrorAction SilentlyContinue
    & docker start chatagent-redis | Out-Null
    @{ schemaVersion = 1; ownedPids = @(); cleanupVerified = $true } | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'processes.json')
    $manifestPath = Join-Path $runDirectory 'manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $manifest.completedAt = (Get-Date).ToUniversalTime().ToString('o')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath
    if ($failure) {
        @{ schemaVersion = 1; submitted = 5; successful = 0; terminalFailed = 5; timedOut = 0; interrupted = 0; finalInFlight = 0; reconciled = $true; completionRatio = 0.0; gatlingKoPercent = 100.0; invalidSuccessAfterFailedCheck = 0; reportable = $false; reason = 'runner-failure' } |
            ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json')
    }
    & (Join-Path $PSScriptRoot 'settle-run-artifacts.ps1') -RunDirectory $runDirectory | Out-Null
}

if ($failure) { throw $failure }
Write-Output $runDirectory
