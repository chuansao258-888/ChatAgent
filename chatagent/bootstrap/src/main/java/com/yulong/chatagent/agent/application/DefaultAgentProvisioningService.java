package com.yulong.chatagent.agent.application;

import com.yulong.chatagent.admin.port.AgentRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.AgentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ensures each user has at least one runtime agent so chat-first flows work
 * even when the UI no longer exposes agent management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultAgentProvisioningService {

    private static final String DEFAULT_AGENT_NAME = "ChatAgent";
    private static final String DEFAULT_AGENT_DESCRIPTION = "Default chat assistant";
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are ChatAgent, a helpful general assistant.
            Give direct, accurate answers and use the current chat session files when they are relevant.
            Keep responses clear and concise unless the user asks for more detail.
            """;

    private final AgentRepository agentRepository;

    public AgentDTO ensureForUser(String userId) {
        List<AgentDTO> existingAgents = agentRepository.findByUserId(userId);
        for (AgentDTO existingAgent : existingAgents) {
            if (DEFAULT_AGENT_NAME.equals(existingAgent.getName())) {
                return existingAgent;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        AgentDTO defaultAgent = AgentDTO.builder()
                .userId(userId)
                .name(DEFAULT_AGENT_NAME)
                .description(DEFAULT_AGENT_DESCRIPTION)
                .systemPrompt(DEFAULT_SYSTEM_PROMPT)
                .model(AgentDTO.ModelType.DEEPSEEK_CHAT)
                .allowedTools(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .createdAt(now)
                .updatedAt(now)
                .build();

        if (!agentRepository.save(defaultAgent)) {
            throw new BizException("Failed to provision default chat agent");
        }

        log.info("Provisioned default agent: userId={}, agentId={}", userId, defaultAgent.getId());
        return defaultAgent;
    }
}
