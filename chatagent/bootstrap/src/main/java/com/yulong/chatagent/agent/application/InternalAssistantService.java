package com.yulong.chatagent.agent.application;

import com.yulong.chatagent.agent.port.AgentRepository;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.AgentDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves the single internal assistant used by the chat entrypoint.
 *
 * <p>Phase 0 intentionally keeps the runtime agent table in place, but users
 * no longer create or select assistants themselves. The active assistant is a
 * fixed internal record provisioned by database migration.</p>
 */
@Service
@RequiredArgsConstructor
public class InternalAssistantService {

    public static final String SYSTEM_USER_ID = "6e8b6d19-4cf7-4f74-9a80-2c7d4f6d6eb5";
    public static final String SYSTEM_ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
    public static final String SYSTEM_USERNAME = "__system_assistant__";
    public static final String SYSTEM_ASSISTANT_NAME = "ChatAgent";

    private final AgentRepository agentRepository;

    public String getRequiredAssistantId() {
        return getRequiredAssistant().getId();
    }

    public AgentDTO getRequiredAssistant() {
        AgentDTO assistant = agentRepository.findById(SYSTEM_ASSISTANT_ID);
        if (assistant == null) {
            throw new BizException("System assistant is not initialized. Run the latest database migrations first.");
        }
        return assistant;
    }
}
