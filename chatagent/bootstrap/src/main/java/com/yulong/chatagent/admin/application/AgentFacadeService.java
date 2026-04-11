package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.UpsertAgentRequest;
import com.yulong.chatagent.admin.model.vo.AgentVO;

import java.util.List;

/**
 * Facade for administrator-managed agent configuration.
 */
public interface AgentFacadeService {

    /**
     * Lists all configured agents for the current admin scope.
     */
    List<AgentVO> getAgents();

    /**
     * Creates a new agent definition and returns its id.
     */
    String createAgent(UpsertAgentRequest request);

    /**
     * Deletes one agent definition.
     */
    void deleteAgent(String agentId);

    /**
     * Updates one agent definition.
     */
    void updateAgent(String agentId, UpsertAgentRequest request);
}
