package com.yulong.chatagent.load;

import io.gatling.javaapi.core.Simulation;

/** Scenario-owned routing fixture workload shell; held execution is implemented in PHASE-05. */
public class RoutingResilienceSimulation extends Simulation {
    public RoutingResilienceSimulation() {
        throw new IllegalStateException(
                "RoutingResilienceSimulation is a PHASE-03 shell; use run-routing-resilience.ps1 -DryRun.");
    }
}
