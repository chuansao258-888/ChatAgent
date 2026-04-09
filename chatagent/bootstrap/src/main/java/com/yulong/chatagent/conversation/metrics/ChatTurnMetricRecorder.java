package com.yulong.chatagent.conversation.metrics;

import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.support.persistence.entity.ChatSession;
import com.yulong.chatagent.support.persistence.entity.ChatTurnMetric;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionMapper;
import com.yulong.chatagent.support.persistence.mapper.ChatTurnMetricMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Persists one dashboard metric row per chat turn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatTurnMetricRecorder {

    private final ChatTurnMetricMapper chatTurnMetricMapper;
    private final ChatSessionMapper chatSessionMapper;

    public void record(ChatEvent event, AgentRunResult result) {
        if (event == null || result == null || !StringUtils.hasText(event.getSessionId())) {
            return;
        }
        ChatSession chatSession = chatSessionMapper.selectById(event.getSessionId());
        if (chatSession == null || !StringUtils.hasText(chatSession.getUserId())) {
            log.warn("Skip chat turn metric because session context is missing: sessionId={}, turnId={}",
                    event.getSessionId(), event == null ? null : event.getTurnId());
            return;
        }

        chatTurnMetricMapper.insert(ChatTurnMetric.builder()
                .sessionId(event.getSessionId())
                .userId(chatSession.getUserId())
                .turnId(event.getTurnId())
                .agentId(event.getAgentId())
                .status(result.status().name())
                .errorType(result.errorType())
                .durationMs(result.durationMs())
                .knowledgeHit(result.knowledgeHit())
                .createdAt(LocalDateTime.now())
                .build());
    }
}
