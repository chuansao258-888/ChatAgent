package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;

import java.util.List;

/** Immutable, sanitized input to the Phase 2.5 understanding owner. */
public record IntentUnderstandingRequest(
        String agentId,
        String sessionId,
        String userInput,
        List<RecentTurn> recentTurns,
        boolean contextAvailable,
        boolean contextTruncated,
        PendingIntentResolution pendingResolution,
        IntentTreeSnapshot snapshot,
        String sessionAssetSummary,
        AgentExecutionMode executionMode
) {
    public IntentUnderstandingRequest {
        userInput = userInput == null ? "" : userInput;
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        executionMode = executionMode == null ? AgentExecutionMode.REACT : executionMode;
    }

    public record RecentTurn(String role, String text) {
        public RecentTurn {
            role = role == null ? "" : role;
            text = text == null ? "" : text;
        }
    }
}
