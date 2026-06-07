package com.yulong.chatagent.eval.v2.memory;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 {@link ExecutionCondition} that probes required infrastructure services
 * (Ollama, Milvus, LLM provider, PostgreSQL) before Spring context is loaded.
 *
 * <p>Runs during test discovery, so unavailable infrastructure results in graceful
 * skip (Tests run: N, Skipped: N) instead of Spring context startup failure.
 */
public class MemoryInfrastructureCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean available = MemoryExportEvalTest.probeAll();
        if (available) {
            return ConditionEvaluationResult.enabled("All Phase 10b infrastructure services available");
        }
        return ConditionEvaluationResult.disabled(
                "Phase 10b memory real-export infrastructure not fully available; skipping real-model memory tests");
    }
}
