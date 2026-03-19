package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateAgentRequest;
import com.yulong.chatagent.admin.model.request.UpdateAgentRequest;
import com.yulong.chatagent.admin.model.response.CreateAgentResponse;
import com.yulong.chatagent.admin.model.response.GetAgentsResponse;

/**
 * Facade for administrator-managed agent configuration.
 */
public interface AgentFacadeService {

    /**
     * Lists all configured agents.
     *
     * @return agent list response
     */
    GetAgentsResponse getAgents();

    /**
     * Creates a new agent definition.
     *
     * @param request create agent request
     * @return created agent response
     */
    CreateAgentResponse createAgent(CreateAgentRequest request);

    /**
     * Deletes one agent definition.
     *
     * @param agentId agent identifier
     */
    void deleteAgent(String agentId);

    /**
     * Updates one agent definition.
     *
     * @param agentId agent identifier
     * @param request update request payload
     */
    void updateAgent(String agentId, UpdateAgentRequest request);
}

