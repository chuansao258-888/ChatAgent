package com.yulong.chatagent.agent;

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
}
