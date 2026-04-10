param(
    [int]$Port = 8090,
    [string]$HostName = "localhost"
)

$ErrorActionPreference = "Stop"

$endpoint = "http://$HostName`:$Port/mcp"

Add-Type -AssemblyName System.Net.Http

$handler = [System.Net.Http.HttpClientHandler]::new()
$client = [System.Net.Http.HttpClient]::new($handler)

try {
    $initializePayload = @{
        jsonrpc = "2.0"
        id = 1
        method = "initialize"
        params = @{
            protocolVersion = "2025-03-26"
            capabilities = @{}
            clientInfo = @{
                name = "chatagent-local-smoke"
                version = "1.0.0"
            }
        }
    } | ConvertTo-Json -Depth 6

    $initRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $endpoint)
    $null = $initRequest.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream")
    $initRequest.Content = [System.Net.Http.StringContent]::new($initializePayload, [System.Text.Encoding]::UTF8, "application/json")

    $initResponse = $client.SendAsync($initRequest).GetAwaiter().GetResult()
    $initBody = $initResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()

    if (-not $initResponse.IsSuccessStatusCode) {
        throw "Initialize failed with HTTP $([int]$initResponse.StatusCode): $initBody"
    }

    $sessionIdValues = [System.Collections.Generic.IEnumerable[string]]$null
    $sessionId = $null
    if ($initResponse.Headers.TryGetValues("Mcp-Session-Id", [ref]$sessionIdValues)) {
        $sessionId = ($sessionIdValues | Select-Object -First 1)
    }

    if (-not $sessionId) {
        throw "Server responded without Mcp-Session-Id header."
    }

    $toolListPayload = @{
        jsonrpc = "2.0"
        id = 2
        method = "tools/list"
        params = @{}
    } | ConvertTo-Json -Depth 6

    $toolListRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $endpoint)
    $null = $toolListRequest.Headers.TryAddWithoutValidation("Accept", "application/json, text/event-stream")
    $null = $toolListRequest.Headers.TryAddWithoutValidation("Mcp-Session-Id", $sessionId)
    $toolListRequest.Content = [System.Net.Http.StringContent]::new($toolListPayload, [System.Text.Encoding]::UTF8, "application/json")

    $toolListResponse = $client.SendAsync($toolListRequest).GetAwaiter().GetResult()
    $toolListBody = $toolListResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()

    if (-not $toolListResponse.IsSuccessStatusCode) {
        throw "tools/list failed with HTTP $([int]$toolListResponse.StatusCode): $toolListBody"
    }

    Write-Host "Initialize OK: HTTP $([int]$initResponse.StatusCode)" -ForegroundColor Green
    Write-Host "Session ID: $sessionId" -ForegroundColor Green
    Write-Host ""
    Write-Host "Initialize payload:" -ForegroundColor Cyan
    Write-Output $initBody
    Write-Host ""
    Write-Host "tools/list payload:" -ForegroundColor Cyan
    Write-Output $toolListBody
}
finally {
    $client.Dispose()
    $handler.Dispose()
}
