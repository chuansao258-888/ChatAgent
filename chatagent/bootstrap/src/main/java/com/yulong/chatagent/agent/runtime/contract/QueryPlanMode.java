package com.yulong.chatagent.agent.runtime.contract;

/**
 * Modes a {@link QueryPlan} can take.
 *
 * <p>Current construction derives {@code NONE}, {@code SINGLE_QUERY}, and
 * {@code MULTI_QUERY}; {@code DECOMPOSED} is reserved for ordered sub-question planning.</p>
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
