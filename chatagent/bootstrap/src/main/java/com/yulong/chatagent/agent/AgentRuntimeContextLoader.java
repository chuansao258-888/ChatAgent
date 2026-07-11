package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.intent.application.IntentResolution;

/**
 * Loads all runtime dependencies required to create a chat agent instance.
 */
public interface AgentRuntimeContextLoader {
    /**
     * Resolves agent definition, memory, tool callbacks, and knowledge summaries.
     *
     * @param agentId agent identifier
     * @param chatSessionId chat session identifier
     * @return fully assembled runtime context
     */
    AgentRuntimeContext load(String agentId, String chatSessionId);

    default AgentRuntimeContext load(String agentId,
                                     String chatSessionId,
                                     IntentResolution intentResolution,
                                     String rewrittenInput) {
        return load(agentId, chatSessionId);
    }

    default AgentRuntimeContext load(String agentId,
                                     String chatSessionId,
                                     IntentResolution intentResolution,
                                     String rewrittenInput,
                                     AgentExecutionMode executionMode) {
        return load(agentId, chatSessionId, intentResolution, rewrittenInput);
    }

    default AgentRuntimeContext load(String agentId,
                                     String chatSessionId,
                                     IntentResolution intentResolution,
                                     String rewrittenInput,
                                     AgentExecutionMode executionMode,
                                     String currentUserInput) {
        return load(agentId, chatSessionId, intentResolution, rewrittenInput, executionMode);
    }

    default AgentRuntimeContext load(String agentId,
                                     String chatSessionId,
                                     IntentResolution intentResolution,
                                     String rewrittenInput,
                                     AgentExecutionMode executionMode,
                                     String currentUserInput,
                                     TurnExecutionContract executionContract) {
        if (executionContract != null) {
            throw new UnsupportedOperationException(
                    "Runtime context loader must explicitly preserve an enforced turn contract");
        }
        return load(agentId, chatSessionId, intentResolution, rewrittenInput, executionMode, currentUserInput);
    }
}
