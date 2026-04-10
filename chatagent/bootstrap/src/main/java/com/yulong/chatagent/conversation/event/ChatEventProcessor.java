package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.support.chat.ChatModelAvailability;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.metrics.ChatTurnMetricRecorder;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared chat-turn processor reused by both the legacy in-process listener and the MQ consumer path.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ChatEventProcessor {

    private final ChatAgentFactory chatAgentFactory;
    private final ChatModelAvailability chatModelAvailability;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final ConversationTurnCompletionPublisher conversationTurnCompletionPublisher;
    private final SseService sseService;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;
    private final ChatTurnMetricRecorder chatTurnMetricRecorder;
    private final ChatSessionRepository chatSessionRepository;

    public void process(ChatEvent event) {
        try {
            log.info("Dispatching chat event: agentId={}, sessionId={}, userMessageId={}, recentHistorySize={}, intentKind={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), event.getRecentHistorySize(),
                    event.getIntentResolution() == null ? "DEFAULT" : event.getIntentResolution().kind());

            if (!chatModelAvailability.hasConfiguredProvider()) {
                log.warn("No chat model provider configured: sessionId={}, userMessageId={}",
                        event.getSessionId(), event.getChatMessageId());
                publishAssistantMessage(
                        event,
                        """
                                ChatAgent is running without a configured chat model.
                                I received your message: %s

                                To enable real AI responses, set either CHATAGENT_DEEPSEEK_API_KEY or CHATAGENT_ZHIPUAI_API_KEY and restart the backend.
                                """.formatted(event.getUserInput())
                );
                recordMetricQuietly(
                        event,
                        new AgentRunResult(AgentRunResult.Status.ERROR, 0L, "MODEL_NOT_CONFIGURED", true)
                );
                conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
                return;
            }

            CurrentIntentResolutionHolder.set(event.getIntentResolution());
            ChatAgent chatAgent = chatAgentFactory.create(
                    event.getAgentId(),
                    event.getSessionId(),
                    event.getTurnId(),
                    event.getIntentResolution(),
                    event.getRewrittenInput(),
                    resolveUserId(event)
            );
            AgentRunResult runResult = chatAgent.run();
            recordMetricQuietly(event, runResult);
            conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
        } finally {
            currentTurnCitationHolder.clear(event.getSessionId(), event.getTurnId());
            currentTurnCitationHolder.clearBySession(event.getSessionId());
            CurrentIntentResolutionHolder.clear();
        }
    }

    /**
     * surgically removes any assistant or tool messages for a given turn.
     * Use this to prepare for a retry or replay to avoid duplicate output.
     */
    public void rollbackTurn(String sessionId, String turnId) {
        log.info("Rolling back chat turn output: sessionId={}, turnId={}", sessionId, turnId);
        chatMessageFacadeService.deleteAssistantAndToolMessagesForTurn(sessionId, turnId);

        // Notify the frontend to clear this turn's messages from the UI state
        sseService.publish(sessionId, SseMessage.builder()
                .type(SseMessage.Type.TURN_ROLLBACK)
                .payload(SseMessage.Payload.builder()
                        .turnId(turnId)
                        .build())
                .build());
    }

    /**
     * Persists a fallback assistant message so failed async work is still visible in the user's current stream.
     */
    public void publishFailure(ChatEvent event, Exception ex) {
        try {
            recordMetricQuietly(event, resolveFailureMetric(ex));

            // Ensure any partial output from the failed attempt is cleared before showing the error
            rollbackTurn(event.getSessionId(), event.getTurnId());

            publishAssistantMessage(event, "Sorry, the agent failed to process this request. Please try again.");
            conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
        } catch (Exception failureHandlingException) {
            log.error("Failed to publish fallback error message: agentId={}, sessionId={}, userMessageId={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), failureHandlingException);
        }
    }

    private void publishAssistantMessage(ChatEvent event, String content) {
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .sessionId(event.getSessionId())
                .turnId(event.getTurnId())
                .content(content)
                .build();
        CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(chatMessage.getChatMessageId());

        ChatMessageVO chatMessageVO = chatMessageConverter.toVO(chatMessageDTO);
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(chatMessageVO)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .build())
                .build();
        sseService.publish(event.getSessionId(), sseMessage);
        sseService.publish(event.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_ERROR)
                .payload(SseMessage.Payload.builder()
                        .statusText("Agent failed to process this request. Please try again.")
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .build())
                .build());
        sseService.publish(event.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_DONE)
                .payload(SseMessage.Payload.builder()
                        .done(true)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .build())
                .build());
    }

    private AgentRunResult resolveFailureMetric(Exception ex) {
        if (ex instanceof AgentRunException agentRunException && agentRunException.getResult() != null) {
            return agentRunException.getResult();
        }
        return AgentRunResult.failure(0L, true, ex);
    }

    private void recordMetricQuietly(ChatEvent event, AgentRunResult runResult) {
        try {
            chatTurnMetricRecorder.record(event, runResult);
        } catch (RuntimeException metricException) {
            log.warn("Failed to record chat turn metric: sessionId={}, turnId={}, error={}",
                    event == null ? null : event.getSessionId(),
                    event == null ? null : event.getTurnId(),
                    metricException.getMessage());
        }
    }

    private String resolveUserId(ChatEvent event) {
        if (event != null && org.springframework.util.StringUtils.hasText(event.getUserId())) {
            return event.getUserId();
        }
        if (event == null || !org.springframework.util.StringUtils.hasText(event.getSessionId())) {
            return null;
        }
        ChatSessionDTO chatSession = chatSessionRepository.findById(event.getSessionId());
        return chatSession == null ? null : chatSession.getUserId();
    }
}
