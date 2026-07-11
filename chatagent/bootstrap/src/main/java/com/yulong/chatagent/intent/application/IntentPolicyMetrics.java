package com.yulong.chatagent.intent.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Bounded-cardinality, content-free metrics for intent and clarification policy decisions. */
@Component
public class IntentPolicyMetrics {

    private final MeterRegistry meterRegistry;

    public IntentPolicyMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public void recordDecision(IntentDecision decision, IntentPolicyMode mode) {
        increment("chatagent.agent.intent.decision",
                "outcome", decision.outcome().name(),
                "source", decision.decisionSource().name(),
                "confidence", decision.confidenceStatus().name(),
                "mode", mode.name(),
                "profile", boundedProfile(decision.policyProfileVersion()));
    }

    public void recordClassifierFailure(IntentClassifierFailure failure) {
        if (failure != null && failure != IntentClassifierFailure.NONE) {
            increment("chatagent.agent.intent.classifier_failure", "reason", failure.name());
        }
    }

    public void recordTreeQuality(IntentTreeQualityValidator.IntentTreeQualityReport report) {
        if (report == null) {
            return;
        }
        for (IntentTreeQualityValidator.QualityFinding finding : report.findings()) {
            increment("chatagent.agent.intent.tree_quality",
                    "code", finding.code(), "severity", finding.severity().name());
        }
    }

    public void recordClarification(String event, ClarificationResolver.ReplyOutcome outcome) {
        increment("chatagent.agent.intent.clarification_" + event,
                "outcome", outcome == null ? "NONE" : outcome.name());
    }

    private void increment(String name, String... tags) {
        if (meterRegistry != null) {
            meterRegistry.counter(name, tags).increment();
        }
    }

    private String boundedProfile(String profile) {
        if (profile == null || !profile.matches("[A-Za-z0-9._-]{1,32}")) {
            return "invalid";
        }
        return profile;
    }
}
