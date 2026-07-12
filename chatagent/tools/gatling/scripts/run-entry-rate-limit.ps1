param([switch]$DryRun)
& (Join-Path $PSScriptRoot 'new-run-artifacts.ps1') -Scenario 'EntryRateLimitSimulation' -Profile 'capacity-test' -EntryEnabled:$true -AgentRunEnabled:$false -AgentRunMax $null -OverrideSource 'run-entry-rate-limit.ps1' -DryRun:$DryRun
exit $LASTEXITCODE
