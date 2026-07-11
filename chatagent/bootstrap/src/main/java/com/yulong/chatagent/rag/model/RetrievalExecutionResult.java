package com.yulong.chatagent.rag.model;

import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Typed result of one contract-aware retrieval query.
 *
 * <p>The result contains only bounded execution metadata and structured hits.
 * It never stores the query text, scope identifiers, provider payloads, or
 * exception messages. {@code actualSources} lists sources that were actually
 * queried, in execution order.</p>
 */
public record RetrievalExecutionResult(
        RetrievalExecutionOutcome outcome,
        RetrievalSource requestedSource,
        List<RetrievalSource> actualSources,
        boolean policyFallbackApplied,
        List<RetrievalHit> hits,
        ReasonCode reasonCode
) {

    public RetrievalExecutionResult {
        outcome = Objects.requireNonNull(outcome, "outcome cannot be null");
        requestedSource = requestedSource == null ? RetrievalSource.NONE : requestedSource;
        actualSources = actualSources == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(actualSources.stream()
                .filter(Objects::nonNull)
                .toList()));
        hits = hits == null
                ? List.of()
                : List.copyOf(hits.stream().filter(Objects::nonNull).toList());

        if ((outcome == RetrievalExecutionOutcome.BLOCKED_NO_SCOPE
                || outcome == RetrievalExecutionOutcome.FAILED) && reasonCode == null) {
            throw new IllegalArgumentException("blocked and failed outcomes require a safe reason code");
        }
        if (outcome == RetrievalExecutionOutcome.FALLBACK_HIT && !policyFallbackApplied) {
            throw new IllegalArgumentException("fallback hit requires an applied policy fallback");
        }
        if ((outcome == RetrievalExecutionOutcome.DISABLED
                || outcome == RetrievalExecutionOutcome.BLOCKED_NO_SCOPE
                || outcome == RetrievalExecutionOutcome.FAILED) && !hits.isEmpty()) {
            throw new IllegalArgumentException("non-search outcomes cannot contain retrieval hits");
        }
    }

    public static RetrievalExecutionResult disabled(RetrievalSource requestedSource) {
        return new RetrievalExecutionResult(
                RetrievalExecutionOutcome.DISABLED,
                requestedSource,
                List.of(),
                false,
                List.of(),
                null);
    }

    public static RetrievalExecutionResult blocked(RetrievalSource requestedSource, ReasonCode reasonCode) {
        return new RetrievalExecutionResult(
                RetrievalExecutionOutcome.BLOCKED_NO_SCOPE,
                requestedSource,
                List.of(),
                false,
                List.of(),
                reasonCode);
    }

    public static RetrievalExecutionResult noHit(RetrievalSource requestedSource,
                                                  List<RetrievalSource> actualSources,
                                                  boolean policyFallbackApplied,
                                                  List<RetrievalHit> hits) {
        return new RetrievalExecutionResult(
                RetrievalExecutionOutcome.NO_HIT,
                requestedSource,
                actualSources,
                policyFallbackApplied,
                hits,
                null);
    }

    public static RetrievalExecutionResult hit(RetrievalSource requestedSource,
                                                List<RetrievalSource> actualSources,
                                                boolean policyFallbackApplied,
                                                boolean fallbackHit,
                                                List<RetrievalHit> hits) {
        return new RetrievalExecutionResult(
                fallbackHit ? RetrievalExecutionOutcome.FALLBACK_HIT : RetrievalExecutionOutcome.HIT,
                requestedSource,
                actualSources,
                policyFallbackApplied,
                hits,
                null);
    }

    public static RetrievalExecutionResult failed(RetrievalSource requestedSource, ReasonCode reasonCode) {
        return new RetrievalExecutionResult(
                RetrievalExecutionOutcome.FAILED,
                requestedSource,
                List.of(),
                false,
                List.of(),
                reasonCode);
    }

    /** Safe, non-user-derived reasons suitable for metrics and final metadata. */
    public enum ReasonCode {
        INVALID_INPUT,
        MISSING_PLAN,
        SESSION_NOT_FOUND,
        NO_ALLOWED_SCOPE,
        DEPENDENCY_FAILURE
    }
}
