package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.port.AgentRepository;
import org.springframework.stereotype.Component;

/**
 * Loads persisted agent definitions from the administrative repository.
 */
@Component
public class AgentDefinitionLoader {

    private final AgentRepository agentRepository;

    public AgentDefinitionLoader(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /**
     * Loads one agent definition by identifier.
     *
     * @param agentId agent identifier
     * @return resolved agent definition
     */
    public AgentDefinition load(String agentId) {
        return new AgentDefinition(agentRepository.findById(agentId));
    }
}
