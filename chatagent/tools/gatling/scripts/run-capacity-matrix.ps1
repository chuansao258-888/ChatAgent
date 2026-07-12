param([switch]$DryRun, [string]$Protocol = 'formal-v1')
& (Join-Path $PSScriptRoot 'new-run-artifacts.ps1') -Scenario 'ChatTurnCapacitySimulation' -Profile 'capacity-test' -EntryEnabled:$false -AgentRunEnabled:$false -AgentRunMax $null -OverrideSource 'profile-default' -Protocol $Protocol -DryRun:$DryRun
exit $LASTEXITCODE
