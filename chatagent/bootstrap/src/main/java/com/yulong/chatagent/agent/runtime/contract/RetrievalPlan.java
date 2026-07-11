package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * The retrieval policy for the turn.
 *
 * <p>This is the single runtime owner of whether retrieval is required, which
 * source it targets, how it falls back, and whether citation is required.</p>
 *
 * @param mode              whether and when retrieval must run
 * @param source            which retrieval source is primary
 * @param scopedKbIds       intent-bound KB ids in scope
 * @param fallbackPolicy    what to do when the primary source misses
 * @param citationRequired  whether evidence-backed answers must carry citations
 */
public record RetrievalPlan(
        RetrievalMode mode,
        RetrievalSource source,
        List<String> scopedKbIds,
        RetrievalFallbackPolicy fallbackPolicy,
        boolean citationRequired
) {
    public RetrievalPlan {
        scopedKbIds = scopedKbIds == null ? List.of() : List.copyOf(scopedKbIds);
    }

    /** A disabled, no-retrieval plan. */
    public static RetrievalPlan disabled() {
        return new RetrievalPlan(RetrievalMode.DISABLED, RetrievalSource.NONE, List.of(), RetrievalFallbackPolicy.NONE, false);
    }
}
