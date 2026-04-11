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

    /**
     * Updates the active intent version on the system assistant.
     *
     * <p>Encapsulates the agent write so that intent-domain callers
     * do not need a direct dependency on {@code AgentRepository}.</p>
     *
     * @param version the intent version to activate
     * @return {@code true} on success
     */
    public boolean updateActiveIntentVersion(int version) {
        AgentDTO assistant = getRequiredAssistant();
        assistant.setActiveIntentVersion(version);
        return agentRepository.update(assistant);
    }

    /**
     * Returns the active intent version for a given agent, or {@code null}
     * if the agent does not exist or has no active version.
     *
     * <p>Provides a read-only gateway so that intent-domain cache management
     * does not need a direct dependency on {@code AgentRepository}.</p>
     *
     * @param agentId agent identifier
     * @return active intent version, or {@code null}
     */
    public Integer getActiveIntentVersion(String agentId) {
        AgentDTO agent = agentRepository.findById(agentId);
        if (agent == null || agent.getActiveIntentVersion() == null || agent.getActiveIntentVersion() <= 0) {
            return null;
        }
        return agent.getActiveIntentVersion();
    }
}
