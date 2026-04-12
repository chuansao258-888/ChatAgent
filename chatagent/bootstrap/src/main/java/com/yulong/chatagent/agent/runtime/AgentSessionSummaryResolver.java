package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the incremental L2 summary for the current chat session.
 */
@Component
public class AgentSessionSummaryResolver {

    private final PromptLoader promptLoader;
    private final ChatSessionSummaryRepository chatSessionSummaryRepository;

    public AgentSessionSummaryResolver(PromptLoader promptLoader,
                                       ChatSessionSummaryRepository chatSessionSummaryRepository) {
        this.promptLoader = promptLoader;
        this.chatSessionSummaryRepository = chatSessionSummaryRepository;
    }

    /**
     * Resolves the stored L2 summary for a chat session.
     *
     * @param chatSessionId chat session identifier
     * @return stored summary, or a default message when unavailable
     */
    public String resolve(String chatSessionId) {
        String fallback = promptLoader.load(PromptConstants.FALLBACK_SESSION_SUMMARY);
        if (!StringUtils.hasText(chatSessionId)) {
            return fallback;
        }
        ChatSessionSummaryDTO summary = chatSessionSummaryRepository.findBySessionId(chatSessionId);
        if (summary == null || !StringUtils.hasText(summary.getSummary())) {
            return fallback;
        }
        return summary.getSummary().trim();
    }
}
