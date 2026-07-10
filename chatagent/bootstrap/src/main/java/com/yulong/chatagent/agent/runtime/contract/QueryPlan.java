package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * Conservative retrieval/search plan built from the original user request and
 * {@link TurnAnalysis}.
 *
 * <p>Phase 1/2 derive {@code NONE}, {@code SINGLE_QUERY}, and {@code MULTI_QUERY}.
 * {@code DECOMPOSED} (ordered sub-questions) is deferred to a later phase that
 * owns real target-aware decomposition; Phase 2 represents multi-source
 * comparison as {@code MULTI_QUERY} with distinct per-source query texts.</p>
 *
 * @param mode          plan mode
 * @param operation     intended operation (QA/COMPARE/SUMMARIZE/EXTRACT/VERIFY)
 * @param queries       query specs (one for SINGLE, several for MULTI/DECOMPOSED)
 * @param mustPreserve  user-stated constraints that any rewrite must keep
 */
public record QueryPlan(
        QueryPlanMode mode,
        QueryOperation operation,
        List<QuerySpec> queries,
        List<String> mustPreserve
) {
    public QueryPlan {
        queries = queries == null ? List.of() : List.copyOf(queries);
        mustPreserve = mustPreserve == null ? List.of() : List.copyOf(mustPreserve);
    }

    /** A no-retrieval-query plan. */
    public static QueryPlan none() {
        return new QueryPlan(QueryPlanMode.NONE, QueryOperation.QA, List.of(), List.of());
    }
}
