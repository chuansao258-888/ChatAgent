param(
    [switch]$DryRun,
    [string]$FixtureBaseUrl = 'http://127.0.0.1:8890'
)
& (Join-Path $PSScriptRoot 'new-run-artifacts.ps1') -Scenario 'RoutingResilienceSimulation' -Profile 'resilience-test' -EntryEnabled:$false -AgentRunEnabled:$false -AgentRunMax $null -OverrideSource 'resilience-profile' -FixtureBaseUrl $FixtureBaseUrl -DryRun:$DryRun
exit $LASTEXITCODE
