param([switch]$DryRun)
& (Join-Path $PSScriptRoot 'new-run-artifacts.ps1') -Scenario 'AgentCapacitySimulation' -Profile 'capacity-test' -EntryEnabled:$false -AgentRunEnabled:$true -AgentRunMax 3 -OverrideSource 'run-agent-capacity.ps1' -DryRun:$DryRun
exit $LASTEXITCODE
