package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;

/**
 * Output of routing before query rewriting and execution dispatch.
 */
public record IntentRoutingResult(
        IntentResolution resolution,
        List<IntentNodeDTO> clarificationCandidates,
        String parentPath
) {
    public IntentRoutingResult {
        clarificationCandidates = clarificationCandidates == null ? List.of() : List.copyOf(clarificationCandidates);
    }

    public static IntentRoutingResult none() {
        return new IntentRoutingResult(null, List.of(), null);
    }

    public static IntentRoutingResult resolved(IntentResolution resolution) {
        return new IntentRoutingResult(resolution, List.of(), null);
    }

    public static IntentRoutingResult clarification(List<IntentNodeDTO> candidates, String parentPath) {
        return new IntentRoutingResult(null, candidates, parentPath);
    }

    public boolean requiresClarification() {
        return !clarificationCandidates.isEmpty();
    }

    public boolean hasResolution() {
        return resolution != null;
    }
}

