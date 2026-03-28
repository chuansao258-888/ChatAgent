package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the incremental L2 summary for the current chat session.
 */
@Component
public class AgentSessionSummaryResolver {

    private final ChatSessionSummaryRepository chatSessionSummaryRepository;

    public AgentSessionSummaryResolver(ChatSessionSummaryRepository chatSessionSummaryRepository) {
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
    }

    /**
     * Resolves the stored L2 summary for a chat session.
     *
     * @param chatSessionId chat session identifier
     * @return stored summary, or a default message when unavailable
     */
    public String resolve(String chatSessionId) {
        if (!StringUtils.hasText(chatSessionId)) {
            return "No historical context summary available";
        }
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(chatSessionId);
        if (summary == null || !StringUtils.hasText(summary.getSummary())) {
            return "No historical context summary available";
        }
        return summary.getSummary().trim();
    }
}
