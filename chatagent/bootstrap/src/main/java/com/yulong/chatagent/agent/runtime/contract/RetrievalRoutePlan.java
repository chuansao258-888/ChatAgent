package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * One backend-owned retrieval route bound to a {@link QuerySpec}.
 *
 * <p>The opaque {@code key} is safe to carry through a tool call because the
 * actual source, KB scope, and fallback remain server-side contract data. The
 * {@code queryIndex} preserves identity when multiple routes intentionally use
 * the same query text.</p>
 *
 * @param key             opaque per-turn route key
 * @param queryIndex      index into {@link QueryPlan#queries()}
 * @param intentNodeId    resolved intent node ID, or {@code null} for a source-only route
 * @param source          source authorized for this route
 * @param scopedKbIds     KB IDs authorized for this route only
 * @param fallbackPolicy  fallback authorized for this route only
 */
public record RetrievalRoutePlan(
        String key,
        int queryIndex,
        String intentNodeId,
        RetrievalSource source,
        List<String> scopedKbIds,
        RetrievalFallbackPolicy fallbackPolicy
) {
    public RetrievalRoutePlan {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Retrieval route key is required");
        }
        if (queryIndex < 0) {
            throw new IllegalArgumentException("Retrieval route query index must be non-negative");
        }
        if (source == null) {
            throw new IllegalArgumentException("Retrieval route source is required");
        }
        scopedKbIds = scopedKbIds == null ? List.of() : List.copyOf(scopedKbIds);
        fallbackPolicy = fallbackPolicy == null ? RetrievalFallbackPolicy.NONE : fallbackPolicy;
    }
}
