package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.support.persistence.entity.AgentKnowledgeBase;
import com.yulong.chatagent.support.persistence.mapper.AgentKnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-backed implementation of assistant-to-knowledge-base bindings.
 */
@Repository
public class MyBatisAgentKnowledgeBaseRepository implements AgentKnowledgeBaseRepository {

    private final AgentKnowledgeBaseMapper agentKnowledgeBaseMapper;

    public MyBatisAgentKnowledgeBaseRepository(AgentKnowledgeBaseMapper agentKnowledgeBaseMapper) {
        this.agentKnowledgeBaseMapper = agentKnowledgeBaseMapper;
    }

    @Override
    public List<String> findKnowledgeBaseIdsByAgentId(String agentId) {
        return agentKnowledgeBaseMapper.selectKnowledgeBaseIdsByAgentId(agentId);
    }

    @Override
    public void deleteByKnowledgeBaseId(String knowledgeBaseId) {
        agentKnowledgeBaseMapper.deleteByKnowledgeBaseId(knowledgeBaseId);
    }

    @Override
    public void replaceBindings(String agentId, List<String> knowledgeBaseIds) {
        agentKnowledgeBaseMapper.deleteByAgentId(agentId);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String knowledgeBaseId : knowledgeBaseIds) {
            agentKnowledgeBaseMapper.insert(AgentKnowledgeBase.builder()
                    .agentId(agentId)
                    .knowledgeBaseId(knowledgeBaseId)
                    .createdAt(now)
                    .build());
        }
    }
}
