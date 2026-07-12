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
    Get-Content -LiteralPath $Path |
        Where-Object { $_ -match '^\s*[^#][^=]+=' } |
        ForEach-Object {
            $name, $value = $_ -split '=', 2
            [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
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
        Invoke-ChatAgentSqlCommand "DELETE FROM t_mq_outbox WHERE payload ->> 'sessionId' IN ($sessionLiterals); DELETE FROM chat_message WHERE session_id IN ($sessionLiterals); DELETE FROM chat_session WHERE id IN ($sessionLiterals);"
    }
    $username = $Identity.Username
    Invoke-ChatAgentSqlCommand "DELETE FROM t_user WHERE username = '$username';"
}

function Get-ChatAgentQueueSnapshot {
    $raw = & docker exec chatagent-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged --formatter json
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

function Stop-ChatAgentOwnedProcesses([System.Collections.IEnumerable]$Processes) {
    foreach ($process in @($Processes)) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
