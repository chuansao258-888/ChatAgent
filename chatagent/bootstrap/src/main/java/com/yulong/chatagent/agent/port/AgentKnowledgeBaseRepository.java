package com.yulong.chatagent.agent.port;

import java.util.List;

/**
 * Persistence port for assistant-to-knowledge-base bindings.
 */
public interface AgentKnowledgeBaseRepository {

    List<String> findKnowledgeBaseIdsByAgentId(String agentId);

    void deleteByKnowledgeBaseId(String knowledgeBaseId);

    void replaceBindings(String agentId, List<String> knowledgeBaseIds);
}
