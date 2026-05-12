package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析当前会话的 L2 增量摘要。
 * <p>
 * L2 摘要补偿 L1 短期记忆窗口之外的历史内容，最终会被拼进 system prompt。
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
     * 查询并返回指定会话的历史摘要。
     *
     * @param chatSessionId 会话 ID
     * @return 已存储摘要；不存在时返回 fallback 文案
     */
    public String resolve(String chatSessionId) {
        // fallback 来自 prompt 模板，保证系统提示词里不会出现 null 或空段落。
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
