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
     * Returns long-term memory context for the current session's user.
     * <p>
     * Deprecated: this resolver will be replaced by LongTermMemoryRecallService in Phase 5.
     * For now, returns empty to stop reading from the deprecated user_profile.summary column.
     *
     * @param chatSessionId session ID
     * @return empty string — L3 recall will be injected via the new service
     */
    public String resolve(String chatSessionId) {
        // Phase 1: user_profile.summary is no longer read for runtime L3 memory.
        // Phase 5 will replace this resolver with LongTermMemoryRecallService.
        return "";
    }
}
