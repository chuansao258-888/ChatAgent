package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * Conservative retrieval/search plan built from the original user request and
 * {@link TurnAnalysis}.
 *
 * <p>Phase 1 only derives {@code NONE} (no retrieval query) or
 * {@code SINGLE_QUERY} carrying the rewritten input for one source. Phase 2 will
 * add {@code MULTI_QUERY} and {@code DECOMPOSED} plus a preservation validator.</p>
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
