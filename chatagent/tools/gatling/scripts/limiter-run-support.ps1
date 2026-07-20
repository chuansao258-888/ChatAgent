function Get-ChatAgentPaths {
    $mavenRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
    $workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $mavenRoot '..'))
    [pscustomobject]@{
        MavenRoot = $mavenRoot
        WorkspaceRoot = $workspaceRoot
        EnvironmentFile = Join-Path $workspaceRoot 'docs\env_variables.txt'
    }
}

function Import-ChatAgentLocalEnvironment([string]$Path) {
    $allowed = @(
        'CHATAGENT_DB_PASSWORD', 'CHATAGENT_JWT_SECRET'
    )
    Get-Content -LiteralPath $Path |
        Where-Object { $_ -match '^\s*[^#][^=]+=' } |
        ForEach-Object {
            $separator = $_.IndexOf('=')
            $normalizedName = $_.Substring(0, $separator).Trim()
            if ($allowed -contains $normalizedName) {
                $value = $_.Substring($separator + 1)
                [Environment]::SetEnvironmentVariable($normalizedName, $value.Trim(), 'Process')
            }
        }
}

function Get-ChatAgentBackendJar([string]$MavenRoot) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $MavenRoot 'bootstrap\target') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'Packaged backend jar was not found.' }
    $jar.FullName
}

function Start-ChatAgentTestBackend {
    param(
        [string]$JarPath,
        [string]$MavenRoot,
        [string]$RunDirectory,
        [int]$Port,
        [string]$LogPrefix
    )
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $Port is already in use; refusing to take ownership."
    }
    $argument = '-jar "' + $JarPath + '" --server.port=' + $Port
    Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" `
        -ArgumentList $argument `
        -WorkingDirectory $MavenRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $RunDirectory "$LogPrefix.out.log") `
        -RedirectStandardError (Join-Path $RunDirectory "$LogPrefix.err.log") `
        -PassThru
}

function Wait-ChatAgentHealth([int]$Port, [int]$TimeoutSeconds = 45) {
    foreach ($attempt in 1..$TimeoutSeconds) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:$Port/health" -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch {
            # Retry until the bounded startup deadline.
        }
        Start-Sleep -Seconds 1
    }
    throw "Backend on port $Port did not become healthy within $TimeoutSeconds seconds."
}

function New-ChatAgentTestIdentity {
    param([int]$Port, [int]$SessionCount, [string]$Prefix)
    $username = "$Prefix-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
    $password = 'LoadTest@123456'
    $register = Invoke-RestMethod -Method Post -Uri "http://localhost:$Port/api/auth/register" `
        -ContentType 'application/json' `
        -Body (@{ username = $username; password = $password } | ConvertTo-Json)
    if ($register.code -ne 200 -or -not $register.data.accessToken) {
        throw 'Failed to create the test-owned identity.'
    }
    $token = [string]$register.data.accessToken
    $headers = @{ Authorization = "Bearer $token" }
    $sessions = foreach ($index in 1..$SessionCount) {
        $response = Invoke-RestMethod -Method Post -Uri "http://localhost:$Port/api/chat-sessions" `
            -Headers $headers -ContentType 'application/json' `
            -Body (@{ title = "$Prefix-$index" } | ConvertTo-Json)
        if ($response.code -ne 200) { throw 'Failed to create a test-owned session.' }
        [string]$response.data
    }
    [pscustomobject]@{ Username = $username; AccessToken = $token; SessionIds = @($sessions) }
}

function Invoke-ChatAgentSqlScalar([string]$Sql) {
    $value = & docker exec chatagent-postgres psql -U app -d chatagent -tAc $Sql
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL audit query failed.' }
    [long]($value.Trim())
}

function Invoke-ChatAgentSqlCommand([string]$Sql) {
    & docker exec chatagent-postgres psql -U app -d chatagent -v ON_ERROR_STOP=1 -c $Sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL cleanup command failed.' }
}

function Remove-ChatAgentTestIdentity {
    param([int]$Port, $Identity)
    if (-not $Identity) { return }
    $headers = @{ Authorization = "Bearer $($Identity.AccessToken)" }
    foreach ($sessionId in @($Identity.SessionIds)) {
        try {
            Invoke-RestMethod -Method Delete -Uri "http://localhost:$Port/api/chat-sessions/$sessionId" `
                -Headers $headers | Out-Null
        } catch {
            # SQL cleanup below remains the final authority for test-owned rows.
        }
    }
    $sessionLiterals = @($Identity.SessionIds | ForEach-Object { "'$_'" }) -join ','
    if ($sessionLiterals) {
        Invoke-ChatAgentSqlCommand "DELETE FROM t_tool_execution_journal WHERE session_id IN ($sessionLiterals); DELETE FROM t_chat_turn_metric WHERE session_id IN ($sessionLiterals); DELETE FROM chat_session_summary_segment WHERE session_id IN ($sessionLiterals); DELETE FROM chat_session_summary WHERE session_id IN ($sessionLiterals); DELETE FROM chat_session_file WHERE session_id IN ($sessionLiterals); DELETE FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionLiterals); DELETE FROM chat_message WHERE session_id IN ($sessionLiterals); DELETE FROM chat_session WHERE id IN ($sessionLiterals);"
    }
    $username = $Identity.Username
    Invoke-ChatAgentSqlCommand "DELETE FROM memory_promotion_job WHERE user_id IN (SELECT id FROM t_user WHERE username = '$username'); DELETE FROM memory_item WHERE user_id IN (SELECT id FROM t_user WHERE username = '$username'); DELETE FROM agent WHERE user_id IN (SELECT id FROM t_user WHERE username = '$username'); DELETE FROM t_user WHERE username = '$username';"
}

function Remove-ChatAgentTestUsersByPrefix([string]$Prefix) {
    if ($Prefix -notmatch '^[a-z0-9-]{8,80}$') {
        throw 'Refusing bulk cleanup for an unsafe test-user prefix.'
    }
    $userFilter = "username LIKE '$Prefix-%'"
    $sessionFilter = "SELECT id FROM chat_session WHERE user_id IN (SELECT id FROM t_user WHERE $userFilter)"
    Invoke-ChatAgentSqlCommand "DELETE FROM t_tool_execution_journal WHERE session_id IN ($sessionFilter); DELETE FROM t_chat_turn_metric WHERE session_id IN ($sessionFilter); DELETE FROM chat_session_summary_segment WHERE session_id IN ($sessionFilter); DELETE FROM chat_session_summary WHERE session_id IN ($sessionFilter); DELETE FROM chat_session_file WHERE session_id IN ($sessionFilter); DELETE FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionFilter); DELETE FROM chat_message WHERE session_id IN ($sessionFilter); DELETE FROM chat_session WHERE id IN ($sessionFilter); DELETE FROM memory_promotion_job WHERE user_id IN (SELECT id FROM t_user WHERE $userFilter); DELETE FROM memory_item WHERE user_id IN (SELECT id FROM t_user WHERE $userFilter); DELETE FROM agent WHERE user_id IN (SELECT id FROM t_user WHERE $userFilter); DELETE FROM t_user WHERE $userFilter;"
}

function Get-ChatAgentQueueSnapshot {
    $raw = @(& docker exec chatagent-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged --formatter json) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ queue observation failed.' }
    @($raw | ConvertFrom-Json)
}

function Get-ChatAgentRedisCardinality {
    $raw = & docker exec chatagent-redis redis-cli ZCARD chatagent:agent-run:active
    if ($LASTEXITCODE -ne 0) { throw 'Redis permit observation failed.' }
    [int]($raw.Trim())
}

function Get-ChatAgentMetricValue([int]$Port, [string]$MetricName, [string[]]$Tag = @()) {
    $uri = "http://127.0.0.1:$Port/actuator/metrics/$MetricName"
    if ($Tag.Count -gt 0) {
        $uri += '?' + (($Tag | ForEach-Object { 'tag=' + [uri]::EscapeDataString($_) }) -join '&')
    }
    try {
        $response = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 5
    } catch {
        if ($_.Exception.Response.StatusCode -eq 404) { return 0.0 }
        throw
    }
    [double](($response.measurements | Measure-Object value -Sum).Sum)
}

function Get-GatlingGlobalKoPercent([string]$LogPath) {
    $requestCount = $null
    $koCount = $null
    foreach ($line in Get-Content -LiteralPath $LogPath) {
        if ($line -match '^> request count.*\|\s+([0-9,]+)\s+\|') {
            $requestCount = [long]($Matches[1] -replace ',', '')
        }
        if ($line -match '^> KO\s+([0-9,]+)') {
            $koCount = [long]($Matches[1] -replace ',', '')
        }
    }
    if ($null -eq $requestCount -or $requestCount -le 0 -or $null -eq $koCount) {
        throw 'Gatling final report does not contain parseable global request/KO counts.'
    }
    100.0 * [double]$koCount / [double]$requestCount
}

function Get-CapacityReportability {
    param(
        [string]$Purpose,
        [long]$P95Ms,
        [bool]$Drained,
        [double]$CompletionRatio,
        [double]$GatlingKoPercent,
        [long]$FinalInFlight,
        [long]$InvalidSuccessAfterFailedCheck
    )

    $operationalGatesPassed = $Drained -and $CompletionRatio -ge 0.99 -and
        $GatlingKoPercent -lt 1.0 -and $FinalInFlight -eq 0 -and
        $InvalidSuccessAfterFailedCheck -eq 0
    if (-not $operationalGatesPassed) {
        return [ordered]@{ reportable = $false; reason = 'operational-reportability-gate-failed' }
    }
    if ($Purpose -eq 'causal-ab' -and $P95Ms -gt 3000L) {
        return [ordered]@{ reportable = $false; reason = 'causal-p95-gate-failed' }
    }
    [ordered]@{ reportable = $true; reason = 'all-reportability-gates-passed' }
}

function Stop-ChatAgentOwnedProcesses([System.Collections.IEnumerable]$Processes) {
    foreach ($process in @($Processes)) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
