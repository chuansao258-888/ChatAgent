package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateAgentRequest;
import com.yulong.chatagent.admin.model.request.UpdateAgentRequest;
import com.yulong.chatagent.admin.model.response.CreateAgentResponse;
import com.yulong.chatagent.admin.model.response.GetAgentsResponse;
import com.yulong.chatagent.admin.model.vo.AgentVO;
import com.yulong.chatagent.admin.port.AgentRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.persistence.converter.AgentConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class AgentFacadeServiceImpl implements AgentFacadeService {

    private final AgentRepository agentRepository;
    private final AgentConverter agentConverter;

    @Override
    public GetAgentsResponse getAgents() {
        List<AgentDTO> agents = agentRepository.findAll();
        List<AgentVO> result = new ArrayList<>();
        for (AgentDTO agent : agents) {
            result.add(agentConverter.toVO(agent));
        }
        return GetAgentsResponse.builder()
                .agents(result.toArray(new AgentVO[0]))
                .build();
    }

    @Override
    public CreateAgentResponse createAgent(CreateAgentRequest request) {
        AgentDTO agentDTO = agentConverter.toDTO(request);
        LocalDateTime now = LocalDateTime.now();
        agentDTO.setCreatedAt(now);
        agentDTO.setUpdatedAt(now);

        if (!agentRepository.save(agentDTO)) {
            throw new BizException("Failed to create agent");
        }

        return CreateAgentResponse.builder()
                .agentId(agentDTO.getId())
                .build();
    }

    @Override
    public void deleteAgent(String agentId) {
        AgentDTO agent = agentRepository.findById(agentId);
        if (agent == null) {
            throw new BizException("Agent not found: " + agentId);
        }

        if (!agentRepository.deleteById(agentId)) {
            throw new BizException("Failed to delete agent");
        }
    }

    @Override
    public void updateAgent(String agentId, UpdateAgentRequest request) {
        AgentDTO existingAgent = agentRepository.findById(agentId);
        if (existingAgent == null) {
            throw new BizException("Agent not found: " + agentId);
        }

        agentConverter.updateDTOFromRequest(existingAgent, request);
        existingAgent.setUpdatedAt(LocalDateTime.now());

        if (!agentRepository.update(existingAgent)) {
            throw new BizException("Failed to update agent");
        }
    }
}

