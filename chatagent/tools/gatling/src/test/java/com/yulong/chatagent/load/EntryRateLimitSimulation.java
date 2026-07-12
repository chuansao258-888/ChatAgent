package com.yulong.chatagent.load;

import io.gatling.javaapi.core.Simulation;

/** Scenario-owned entry-limiter workload shell; held execution is implemented in PHASE-04. */
public class EntryRateLimitSimulation extends Simulation {
    public EntryRateLimitSimulation() {
        throw new IllegalStateException(
                "EntryRateLimitSimulation is a PHASE-03 shell; use run-entry-rate-limit.ps1 -DryRun.");
    }
}
