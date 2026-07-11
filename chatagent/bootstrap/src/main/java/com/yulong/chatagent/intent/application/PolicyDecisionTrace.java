package com.yulong.chatagent.intent.application;

import java.util.List;

/** Content-free trace suitable for metrics and diagnostic logs. */
public record PolicyDecisionTrace(
        IntentRouteOutcome outcome,
        IntentDecisionSource decisionSource,
        ConfidenceStatus confidenceStatus,
        String profileVersion,
        int candidateCount,
        List<String> reasonCodes
) {
    public PolicyDecisionTrace {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        candidateCount = Math.max(candidateCount, 0);
    }

    public static PolicyDecisionTrace from(IntentDecision decision) {
        return new PolicyDecisionTrace(
                decision.outcome(),
                decision.decisionSource(),
                decision.confidenceStatus(),
                decision.policyProfileVersion(),
                decision.rankedCandidates().size(),
                decision.reasonCodes()
        );
    }
}
