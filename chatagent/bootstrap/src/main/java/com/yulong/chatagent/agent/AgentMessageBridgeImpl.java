package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.rag.model.CitationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default bridge that stores agent output as chat messages and forwards it over SSE.
 */
@Component
public class AgentMessageBridgeImpl implements AgentMessageBridge {

    private final SseService sseService;
    private final ChatMessageConverter chatMessageConverter;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;

    public AgentMessageBridgeImpl(SseService sseService,
                                  ChatMessageConverter chatMessageConverter,
                                  ChatMessageFacadeService chatMessageFacadeService,
                                  CurrentTurnCitationHolder currentTurnCitationHolder) {
        this.sseService = sseService;
        this.chatMessageConverter = chatMessageConverter;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.currentTurnCitationHolder = currentTurnCitationHolder;
    }

    @Override
    public void persistAndPublish(String chatSessionId, String turnId, Message message) {
        // Assistant output becomes one persisted assistant message.
        if (message instanceof AssistantMessage assistantMessage) {
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            List<CitationMetadata> citations = (toolCalls == null || toolCalls.isEmpty())
                    ? currentTurnCitationHolder.take(chatSessionId, turnId)
                    : List.of();
            ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(chatSessionId)
                    .turnId(turnId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(toolCalls)
                            .citations(citations.isEmpty() ? null : citations)
                            .build())
                    .build();
            send(chatMessageDTO);
            return;
        }

        // Tool output becomes one persisted tool message per tool response.
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                        .role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(chatSessionId)
                        .turnId(turnId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                send(chatMessageDTO);
            }
            return;
        }

        throw new IllegalArgumentException("Unsupported message type: " + message.getClass().getName());
    }

    /**
     * Persists a normalized chat message and publishes the resulting view model through SSE.
     *
     * @param chatMessageDTO normalized chat message DTO
     */
    private void send(ChatMessageDTO chatMessageDTO) {
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
        sseService.publish(chatMessageDTO.getSessionId(), sseMessage);
    }
}
