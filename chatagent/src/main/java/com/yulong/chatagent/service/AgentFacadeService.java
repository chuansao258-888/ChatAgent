package com.yulong.chatagent.service;

import com.yulong.chatagent.model.request.CreateAgentRequest;
import com.yulong.chatagent.model.request.UpdateAgentRequest;
import com.yulong.chatagent.model.response.CreateAgentResponse;
import com.yulong.chatagent.model.response.GetAgentsResponse;

public interface AgentFacadeService {
    GetAgentsResponse getAgents();

    CreateAgentResponse createAgent(CreateAgentRequest request);

    void deleteAgent(String agentId);

    void updateAgent(String agentId, UpdateAgentRequest request);
}
