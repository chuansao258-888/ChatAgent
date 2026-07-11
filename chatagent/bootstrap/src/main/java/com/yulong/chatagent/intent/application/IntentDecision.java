package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/** Typed, content-free route decision transported inside {@code TurnAnalysis}. */
public record IntentDecision(
        IntentRouteOutcome outcome,
        String primaryNodeId,
        List<String> secondaryNodeIds,
        List<IntentCandidateEvidence> rankedCandidates,
        List<MissingDimension> missingDimensions,
        IntentDecisionSource decisionSource,
        double calibratedConfidence,
        ConfidenceStatus confidenceStatus,
        String policyProfileVersion,
        List<String> reasonCodes
) {
    public IntentDecision {
        if (outcome == null) {
            throw new IllegalArgumentException("Intent decision outcome is required");
        }
        secondaryNodeIds = secondaryNodeIds == null ? List.of() : List.copyOf(secondaryNodeIds);
        rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        missingDimensions = missingDimensions == null ? List.of() : List.copyOf(missingDimensions);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        decisionSource = decisionSource == null ? IntentDecisionSource.SAFE_FALLBACK : decisionSource;
        confidenceStatus = confidenceStatus == null ? ConfidenceStatus.UNCALIBRATED : confidenceStatus;
        policyProfileVersion = policyProfileVersion == null ? "unbound" : policyProfileVersion;
        if (confidenceStatus == ConfidenceStatus.UNCALIBRATED) {
            calibratedConfidence = 0.0d;
        } else {
            calibratedConfidence = Math.max(0.0d, Math.min(calibratedConfidence, 1.0d));
        }
    }

    @JsonIgnore
    public boolean isPassThrough() {
        return outcome == IntentRouteOutcome.GENERAL_CHAT
                || outcome == IntentRouteOutcome.OUT_OF_DOMAIN;
    }

    @JsonIgnore
    public boolean requiresRouteClarification() {
        return outcome == IntentRouteOutcome.AMBIGUOUS_ROUTE;
    }

    @JsonIgnore
    public boolean hasPrimaryNode() {
        return primaryNodeId != null && !primaryNodeId.isBlank();
    }

    public IntentDecision withReasonCodes(List<String> reasons) {
        return new IntentDecision(outcome, primaryNodeId, secondaryNodeIds, rankedCandidates,
                missingDimensions, decisionSource, calibratedConfidence, confidenceStatus,
                policyProfileVersion, reasons);
    }
}
