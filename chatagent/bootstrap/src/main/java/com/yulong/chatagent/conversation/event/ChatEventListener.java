package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.chat.ChatModelAvailability;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async domain-event listener that dispatches chat work to the selected agent.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final ChatAgentFactory chatAgentFactory;
    private final ChatModelAvailability chatModelAvailability;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final ConversationTurnCompletionPublisher conversationTurnCompletionPublisher;
    private final SseService sseService;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;

    /**
     * Creates and runs the target chat agent when a chat event is published.
     *
     * @param event published chat event
     */
    @Async
    @EventListener
    public void handle(ChatEvent event) {
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
                conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
                return;
            }

            CurrentIntentResolutionHolder.set(event.getIntentResolution());
            ChatAgent chatAgent = chatAgentFactory.create(
                    event.getAgentId(),
                    event.getSessionId(),
                    event.getTurnId(),
                    event.getIntentResolution(),
                    event.getRewrittenInput()
            );
            chatAgent.run();
            conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
        } catch (Exception ex) {
            log.error("Failed to process chat event: agentId={}, sessionId={}, userMessageId={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), ex);
            handleFailure(event, ex);
        } finally {
            currentTurnCitationHolder.clear(event.getSessionId(), event.getTurnId());
            CurrentIntentResolutionHolder.clear();
        }
    }

    /**
     * Persists a fallback assistant message and pushes it to the current SSE stream
     * so asynchronous agent failures remain visible to the user.
     *
     * @param event failed chat event
     * @param ex triggering exception
     */
    private void handleFailure(ChatEvent event, Exception ex) {
        try {
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
        sseService.send(event.getSessionId(), sseMessage);
    }
}
