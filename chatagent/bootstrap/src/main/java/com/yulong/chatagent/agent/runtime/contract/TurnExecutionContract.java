package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;

/**
 * Immutable per-turn execution strategy, built before the Agent runtime runs.
 *
 * <p>It is the single owner the plan introduces so intent routing, thinking,
 * RAG, DeepThink, and final repair no longer make independent strategy
 * decisions about the same turn. Phase 1 builds the contract in warn mode: it is
 * present on dispatched turns and emitted to safe debug logs/metrics, but it
 * does not yet change retrieval, tool, or clarification behavior.</p>
 *
 * @param version        contract schema version
 * @param analysis       structured turn understanding
 * @param queryPlan      conservative retrieval/search plan
 * @param intent         intent outcome
 * @param clarification  clarification policy
 * @param retrieval      retrieval policy
 * @param tools          tool policy
 * @param memory         memory policy
 * @param answer         answer constraints
 * @param executionMode  requested runtime mode
 * @param ordering       session-ordering expectation
 */
public record TurnExecutionContract(
        String version,
        TurnAnalysis analysis,
        QueryPlan queryPlan,
        IntentContract intent,
        ClarificationPlan clarification,
        RetrievalPlan retrieval,
        ToolPolicy tools,
        MemoryPolicy memory,
        AnswerContract answer,
        AgentExecutionMode executionMode,
        OrderingPolicy ordering
) {
    /** Current contract schema version. */
    public static final String VERSION = "v1";
}
