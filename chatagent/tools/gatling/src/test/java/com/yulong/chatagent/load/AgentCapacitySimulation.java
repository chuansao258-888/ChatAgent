package com.yulong.chatagent.load;

import io.gatling.javaapi.core.Simulation;

/** Scenario-owned execution-capacity workload shell; held execution is implemented in PHASE-04. */
public class AgentCapacitySimulation extends Simulation {
    public AgentCapacitySimulation() {
        throw new IllegalStateException(
                "AgentCapacitySimulation is a PHASE-03 shell; use run-agent-capacity.ps1 -DryRun.");
    }
}
