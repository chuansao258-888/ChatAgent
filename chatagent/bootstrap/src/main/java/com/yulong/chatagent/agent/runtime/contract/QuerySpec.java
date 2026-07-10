package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * One conservative query inside a {@link QueryPlan}.
 *
 * @param text                  the query text to retrieve with
 * @param source                which retrieval source this query targets
 * @param preservedConstraints  source/object/time/action/comparison constraints that must survive
 */
public record QuerySpec(
        String text,
        RetrievalSource source,
        List<String> preservedConstraints
) {
    public QuerySpec {
        preservedConstraints = preservedConstraints == null ? List.of() : List.copyOf(preservedConstraints);
    }
}
