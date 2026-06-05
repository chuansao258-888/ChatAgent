package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the L2 historical context summary for runtime prompt injection.
 *
 * <p>V2 behavior: returns empty string when no synopsis exists, so the caller
 * omits {@code [Historical Context Summary]} entirely.
 */
@Component
public class AgentSessionSummaryResolver {

    private final ChatSessionSummaryRepository chatSessionSummaryRepository;

    public AgentSessionSummaryResolver(ChatSessionSummaryRepository chatSessionSummaryRepository) {
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
    }

    /**
     * Resolves the V2 synopsis for the given session.
     *
     * @return synopsis text, or empty string if no synopsis exists
     */
    public String resolve(String chatSessionId) {
        if (!StringUtils.hasText(chatSessionId)) {
            return "";
        }
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(chatSessionId);
        if (summary == null || !StringUtils.hasText(summary.getSynopsis())) {
            return "";
        }
        return summary.getSynopsis().trim();
    }
}
