package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;

import java.util.List;

/** Immutable, sanitized turn-preparation input assembled by the conversation owner. */
public record TurnPreparationContext(
        String agentId,
        String sessionId,
        String userInput,
        List<IntentUnderstandingRequest.RecentTurn> recentVisibleTurns,
        boolean contextAvailable,
        boolean contextTruncated,
        String sessionAssetSummary,
        AgentExecutionMode executionMode
) {
    public TurnPreparationContext {
        userInput = userInput == null ? "" : userInput;
        recentVisibleTurns = recentVisibleTurns == null ? List.of() : List.copyOf(recentVisibleTurns);
        executionMode = executionMode == null ? AgentExecutionMode.REACT : executionMode;
    }
}
