package com.yulong.chatagent.agent;

import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.persistence.converter.ChatMessageConverter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

@Component
public class AgentMessageBridgeImpl implements AgentMessageBridge {

    private final SseService sseService;
    private final ChatMessageConverter chatMessageConverter;
    private final ChatMessageFacadeService chatMessageFacadeService;

    public AgentMessageBridgeImpl(SseService sseService,
                                  ChatMessageConverter chatMessageConverter,
                                  ChatMessageFacadeService chatMessageFacadeService) {
        this.sseService = sseService;
        this.chatMessageConverter = chatMessageConverter;
        this.chatMessageFacadeService = chatMessageFacadeService;
    }

    @Override
    public void persistAndPublish(String chatSessionId, Message message) {
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            send(chatMessageDTO);
            return;
        }

        if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                        .role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(chatSessionId)
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
        sseService.send(chatMessageDTO.getSessionId(), sseMessage);
    }
}

