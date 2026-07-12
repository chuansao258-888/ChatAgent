$ErrorActionPreference = 'Stop'

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
} finally {
    $resolved = [System.IO.Path]::GetFullPath($temp)
    $tempRoot = [System.IO.Path]::GetFullPath($env:TEMP)
    if (-not $resolved.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clean a contract-test path outside the OS temp directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

Write-Output 'HARNESS_CONTRACT_PASS'
