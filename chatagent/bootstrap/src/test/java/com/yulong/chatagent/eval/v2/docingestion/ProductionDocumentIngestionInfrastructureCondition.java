package com.yulong.chatagent.eval.v2.docingestion;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 {@link ExecutionCondition} that probes required infrastructure services
 * (PostgreSQL, Redis, RabbitMQ, Ollama bge-m3, Milvus, and MinerU) before the
 * Spring context is loaded.
 *
 * <p>Runs during test discovery, so unavailable infrastructure results in graceful
 * skip (Tests run: N, Skipped: N) instead of Spring context startup failure.
 */
public class ProductionDocumentIngestionInfrastructureCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean available = ProductionDocumentIngestionEvalTest.probeAll();
        if (available) {
            return ConditionEvaluationResult.enabled("All Phase 10a document-ingestion infrastructure services available");
        }
        return ConditionEvaluationResult.disabled(
                "Phase 10a document-ingestion infrastructure not fully available "
                        + "(requires PostgreSQL, Redis, RabbitMQ, Ollama, Milvus, MinerU); skipping real-model tests");
    }
}
