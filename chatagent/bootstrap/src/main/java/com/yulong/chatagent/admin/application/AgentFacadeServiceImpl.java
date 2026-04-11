package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.admin.model.request.UpsertAgentRequest;
import com.yulong.chatagent.admin.model.vo.AgentVO;
import com.yulong.chatagent.agent.port.AgentRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.admin.converter.AgentConverter;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of administrative agent configuration management.
 */
@Service
@AllArgsConstructor
public class AgentFacadeServiceImpl implements AgentFacadeService {

    private final AgentRepository agentRepository;
    private final AgentConverter agentConverter;

    @Override
    public List<AgentVO> getAgents() {
        String userId = requireCurrentUserId();
        List<AgentDTO> agents = agentRepository.findByUserId(userId);
        List<AgentVO> result = new ArrayList<>();
        for (AgentDTO agent : agents) {
            result.add(agentConverter.toVO(agent));
        }
        return result;
    }

    @Override
    public String createAgent(UpsertAgentRequest request) {
        String userId = requireCurrentUserId();
        AgentDTO agentDTO = agentConverter.toDTO(request);
        agentDTO.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        agentDTO.setCreatedAt(now);
        agentDTO.setUpdatedAt(now);

        if (!agentRepository.save(agentDTO)) {
            throw new BizException("Failed to create agent");
        }

        return agentDTO.getId();
    }

    @Override
    public void deleteAgent(String agentId) {
        requireOwnedAgent(agentId, requireCurrentUserId());

        if (!agentRepository.deleteById(agentId)) {
            throw new BizException("Failed to delete agent");
        }
    }

    @Override
    public void updateAgent(String agentId, UpsertAgentRequest request) {
        String userId = requireCurrentUserId();
        AgentDTO existingAgent = requireOwnedAgent(agentId, userId);

        agentConverter.updateDTOFromRequest(existingAgent, request);
        existingAgent.setUpdatedAt(LocalDateTime.now());

        if (!agentRepository.update(existingAgent)) {
            throw new BizException("Failed to update agent");
        }
    }

    private String requireCurrentUserId() {
        return UserContext.requireUser().getUserId();
    }

    private AgentDTO requireOwnedAgent(String agentId, String userId) {
        AgentDTO agent = agentRepository.findById(agentId);
        if (agent == null || !userId.equals(agent.getUserId())) {
            throw new BizException("Agent not found: " + agentId);
        }
        return agent;
    }
}
