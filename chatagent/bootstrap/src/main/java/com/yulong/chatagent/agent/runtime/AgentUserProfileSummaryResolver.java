package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.user.application.UserProfileService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the persistent user profile summary visible to one chat session.
 */
@Component
public class AgentUserProfileSummaryResolver {

    private final ChatSessionRepository chatSessionRepository;
    private final UserProfileService userProfileService;

    public AgentUserProfileSummaryResolver(ChatSessionRepository chatSessionRepository,
                                           UserProfileService userProfileService) {
        this.chatSessionRepository = chatSessionRepository;
        this.userProfileService = userProfileService;
    }

    /**
     * Resolves the stored user profile summary for the owner of one chat session.
     *
     * @param chatSessionId chat session identifier
     * @return stored summary, or a default sentence when unavailable
     */
    public String resolve(String chatSessionId) {
        ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
        if (chatSession == null || !StringUtils.hasText(chatSession.getUserId())) {
            return "No persistent user profile available";
        }
        return userProfileService.getUserProfileSummary(chatSession.getUserId());
    }
}
