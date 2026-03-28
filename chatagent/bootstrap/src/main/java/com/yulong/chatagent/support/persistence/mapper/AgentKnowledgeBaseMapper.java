package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.AgentKnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code agent_knowledge_base} table.
 */
@Mapper
public interface AgentKnowledgeBaseMapper {
    int insert(AgentKnowledgeBase binding);

    int deleteByAgentId(@Param("agentId") String agentId);

    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    List<String> selectKnowledgeBaseIdsByAgentId(@Param("agentId") String agentId);
}
