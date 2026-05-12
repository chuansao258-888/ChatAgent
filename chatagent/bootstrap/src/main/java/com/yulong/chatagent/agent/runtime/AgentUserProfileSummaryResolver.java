package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.user.application.UserProfileService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析当前会话所属用户的 L3 用户画像摘要。
 * <p>
 * L3 是跨会话持久化信息，用于让 Agent 了解用户长期偏好和背景。
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
     * 根据会话找到用户，再读取该用户的画像摘要。
     *
     * @param chatSessionId 会话 ID
     * @return 用户画像摘要；不可用时返回默认文案
     */
    public String resolve(String chatSessionId) {
        // 会话未绑定用户时不能跨用户查画像，直接返回无画像提示。
        ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
        if (chatSession == null || !StringUtils.hasText(chatSession.getUserId())) {
            return "No persistent user profile available";
        }
        return userProfileService.getUserProfileSummary(chatSession.getUserId());
    }
}
