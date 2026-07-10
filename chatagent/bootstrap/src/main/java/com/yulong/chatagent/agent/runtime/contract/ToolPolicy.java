package com.yulong.chatagent.agent.runtime.contract;

import java.util.List;

/**
 * Tool policy for the turn.
 *
 * <p>Carries the agent-allowed tool names so Phase 3 can make
 * {@code AgentToolCallbackFactory} consume the contract instead of raw
 * {@code IntentResolution}. Phase 1 only records the allowed-tool set.</p>
 *
 * @param allowedTools     tool names the agent config permits for this turn
 * @param retrievalVisible whether the retrieval tool is visible to the runtime
 */
public record ToolPolicy(
        List<String> allowedTools,
        boolean retrievalVisible
) {
    public ToolPolicy {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
