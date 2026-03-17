package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.admin.port.AgentRepository;
import org.springframework.stereotype.Component;

@Component
public class AgentDefinitionLoader {

    private final AgentRepository agentRepository;

    public AgentDefinitionLoader(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public AgentDefinition load(String agentId) {
        return new AgentDefinition(agentRepository.findById(agentId));
    }
}
