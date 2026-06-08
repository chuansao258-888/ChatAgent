package com.yulong.chatagent.eval.v2.agentmodule;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 {@link ExecutionCondition} that probes required infrastructure services
 * (PostgreSQL, Redis, LLM provider) before Spring context is loaded.
 *
 * <p>Runs during test discovery, so unavailable infrastructure results in graceful
 * skip (Tests run: N, Skipped: N) instead of Spring context startup failure.
 *
 * <p>Unlike Phase 10b (memory eval), this does NOT probe Ollama or Milvus —
 * agent module eval only needs the database, Redis cache, and LLM provider.
 */
public class AgentModuleInfrastructureCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean available = AgentModuleExportEvalTest.probeAll();
        if (available) {
            return ConditionEvaluationResult.enabled("All Phase 10c infrastructure services available");
        }
        return ConditionEvaluationResult.disabled(
                "Phase 10c agent module real-export infrastructure not fully available; "
                        + "skipping real-model agent module tests");
    }
}
