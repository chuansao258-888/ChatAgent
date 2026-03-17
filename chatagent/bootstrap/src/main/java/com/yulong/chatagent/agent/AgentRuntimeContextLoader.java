package com.yulong.chatagent.agent;

public interface AgentRuntimeContextLoader {
    AgentRuntimeContext load(String agentId, String chatSessionId);
}
