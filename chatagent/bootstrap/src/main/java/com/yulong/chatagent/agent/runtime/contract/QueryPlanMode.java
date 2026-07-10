package com.yulong.chatagent.agent.runtime.contract;

/**
 * Modes a {@link QueryPlan} can take.
 *
 * <p>Phase 1 only derives {@code NONE} and {@code SINGLE_QUERY}. {@code MULTI_QUERY}
 * and {@code DECOMPOSED} are added when Phase 2 builds source-specific query specs.</p>
 */
public enum QueryPlanMode {
    /** No retrieval/search query is needed. */
    NONE,
    /** One conservative query for one source. */
    SINGLE_QUERY,
    /** Multiple source-specific queries (e.g. KB + file comparison). */
    MULTI_QUERY,
    /** Ordered sub-questions for comparison, verification, or synthesis. */
    DECOMPOSED
}
