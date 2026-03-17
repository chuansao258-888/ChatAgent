package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateAgentRequest;
import com.yulong.chatagent.admin.model.request.UpdateAgentRequest;
import com.yulong.chatagent.admin.model.response.CreateAgentResponse;
import com.yulong.chatagent.admin.model.response.GetAgentsResponse;

public interface AgentFacadeService {

    GetAgentsResponse getAgents();

    CreateAgentResponse createAgent(CreateAgentRequest request);

    void deleteAgent(String agentId);

    void updateAgent(String agentId, UpdateAgentRequest request);
}

