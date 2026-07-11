package com.yulong.chatagent.rag.model;

import com.yulong.chatagent.agent.runtime.contract.RetrievalMode;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.intent.model.IntentKind;

import java.util.LinkedHashSet;
import java.util.List;

/** Sanitized, turn-level retrieval observation safe for run and message metadata. */
public record RetrievalOutcomeMetadata(
        String contractVersion,
        IntentKind intentKind,
        boolean retrievalRequired,
        RetrievalSource requestedSource,
        List<RetrievalSource> actualSources,
        String retrievalOutcome,
        RetrievalExecutionOutcome retrievalOutcomeDetail,
        boolean policyFallbackApplied,
        boolean citationRequired,
        int hitCount
) {
    public RetrievalOutcomeMetadata {
        actualSources = actualSources == null ? List.of() : List.copyOf(actualSources);
        hitCount = Math.max(hitCount, 0);
    }

    public static RetrievalOutcomeMetadata merge(RetrievalOutcomeMetadata previous,
                                                 RetrievalExecutionResult result,
                                                 TurnExecutionContract contract) {
        boolean required = contract != null && contract.retrieval() != null
                && contract.retrieval().mode() == RetrievalMode.REQUIRED_BEFORE_ANSWER;
        String coarse = coarseOutcome(result.outcome(), required);
        LinkedHashSet<RetrievalSource> sources = new LinkedHashSet<>();
        if (previous != null) {
            sources.addAll(previous.actualSources());
        }
        sources.addAll(result.actualSources());
        String mergedCoarse = previous == null
                ? coarse : higherPriority(previous.retrievalOutcome(), coarse);
        RetrievalExecutionOutcome detail = previous == null
                ? result.outcome()
                : higherDetail(previous.retrievalOutcomeDetail(), result.outcome());
        return new RetrievalOutcomeMetadata(
                contract == null ? null : contract.version(),
                contract == null || contract.intent() == null ? null : contract.intent().kind(),
                required,
                contract == null || contract.retrieval() == null
                        ? result.requestedSource() : contract.retrieval().source(),
                List.copyOf(sources),
                mergedCoarse,
                detail,
                (previous != null && previous.policyFallbackApplied()) || result.policyFallbackApplied(),
                contract != null && contract.retrieval() != null
                        && contract.retrieval().citationRequired(),
                (previous == null ? 0 : previous.hitCount())
                        + ((result.outcome() == RetrievalExecutionOutcome.HIT
                        || result.outcome() == RetrievalExecutionOutcome.FALLBACK_HIT)
                        ? result.hits().size() : 0));
    }

    private static String coarseOutcome(RetrievalExecutionOutcome outcome, boolean required) {
        return switch (outcome) {
            case HIT, FALLBACK_HIT -> "HIT";
            case NO_HIT -> "MISS";
            case DISABLED -> "SKIPPED";
            case FAILED -> "FAILED";
            case BLOCKED_NO_SCOPE -> required ? "FAILED" : "SKIPPED";
        };
    }

    private static String higherPriority(String left, String right) {
        return priority(right) > priority(left) ? right : left;
    }

    private static int priority(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "FAILED" -> 4;
            case "HIT" -> 3;
            case "MISS" -> 2;
            case "SKIPPED" -> 1;
            default -> 0;
        };
    }

    private static RetrievalExecutionOutcome higherDetail(RetrievalExecutionOutcome left,
                                                           RetrievalExecutionOutcome right) {
        return detailPriority(right) > detailPriority(left) ? right : left;
    }

    private static int detailPriority(RetrievalExecutionOutcome outcome) {
        return switch (outcome) {
            case FAILED -> 6;
            case FALLBACK_HIT -> 5;
            case HIT -> 4;
            case NO_HIT -> 3;
            case BLOCKED_NO_SCOPE -> 2;
            case DISABLED -> 1;
        };
    }
}
