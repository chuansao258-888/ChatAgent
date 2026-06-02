package com.yulong.chatagent.conversation.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.persistence.entity.ChatMessage;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
@AllArgsConstructor
public class ChatMessageConverter {

    private final ObjectMapper objectMapper;

    public ChatMessage toEntity(ChatMessageDTO chatMessageDTO) throws JsonProcessingException {
        Assert.notNull(chatMessageDTO, "ChatMessageDTO cannot be null");
        Assert.notNull(chatMessageDTO.getRole(), "Role cannot be null");

        return ChatMessage.builder()
                .id(chatMessageDTO.getId())
                .sessionId(chatMessageDTO.getSessionId())
                .turnId(chatMessageDTO.getTurnId())
                .turnSeq(chatMessageDTO.getTurnSeq())
                .role(chatMessageDTO.getRole().getRole())
                .content(chatMessageDTO.getContent())
                .metadata(chatMessageDTO.getMetadata() != null
                        ? objectMapper.writeValueAsString(chatMessageDTO.getMetadata())
                        : null)
                .seqNo(chatMessageDTO.getSeqNo())
                .turnCompleted(chatMessageDTO.getTurnCompleted())
                .createdAt(chatMessageDTO.getCreatedAt())
                .updatedAt(chatMessageDTO.getUpdatedAt())
                .build();
    }

    public ChatMessageDTO toDTO(ChatMessage chatMessage) throws JsonProcessingException {
        Assert.notNull(chatMessage, "ChatMessage cannot be null");
        Assert.notNull(chatMessage.getRole(), "Role cannot be null");

        return ChatMessageDTO.builder()
                .id(chatMessage.getId())
                .sessionId(chatMessage.getSessionId())
                .turnId(chatMessage.getTurnId())
                .turnSeq(chatMessage.getTurnSeq())
                .role(ChatMessageDTO.RoleType.fromRole(chatMessage.getRole()))
                .content(chatMessage.getContent())
                .metadata(chatMessage.getMetadata() != null
                        ? objectMapper.readValue(chatMessage.getMetadata(), ChatMessageDTO.MetaData.class)
                        : null)
                .seqNo(chatMessage.getSeqNo())
                .turnCompleted(chatMessage.getTurnCompleted())
                .createdAt(chatMessage.getCreatedAt())
                .updatedAt(chatMessage.getUpdatedAt())
                .build();
    }

    public ChatMessageVO toVO(ChatMessageDTO dto) {
        return ChatMessageVO.builder()
                .id(dto.getId())
                .sessionId(dto.getSessionId())
                .turnId(dto.getTurnId())
                .turnSeq(dto.getTurnSeq())
                .role(dto.getRole())
                .content(dto.getContent())
                .metadata(dto.getMetadata())
                .seqNo(dto.getSeqNo())
                .build();
    }

    public ChatMessageVO toVO(ChatMessage chatMessage) throws JsonProcessingException {
        return toVO(toDTO(chatMessage));
    }

    public ChatMessageDTO toDTO(CreateChatMessageRequest request) {
        Assert.notNull(request, "CreateChatMessageRequest cannot be null");
        Assert.notNull(request.getSessionId(), "SessionId cannot be null");
        Assert.notNull(request.getRole(), "Role cannot be null");

        return ChatMessageDTO.builder()
                .sessionId(request.getSessionId())
                .turnId(request.getTurnId())
                .turnSeq(request.getTurnSeq())
                .role(request.getRole())
                .content(request.getContent())
                .metadata(resolveMetadata(request))
                .build();
    }

    private ChatMessageDTO.MetaData resolveMetadata(CreateChatMessageRequest request) {
        ChatMessageDTO.MetaData metadata = request.getMetadata();
        if (request.getExecutionMode() == null) {
            return metadata;
        }
        ChatMessageDTO.MetaData.MetaDataBuilder builder = ChatMessageDTO.MetaData.builder()
                .executionMode(request.getExecutionMode());
        if (metadata != null) {
            builder.toolResponse(metadata.getToolResponse())
                    .toolCalls(metadata.getToolCalls())
                    .citations(metadata.getCitations())
                    .internal(metadata.getInternal())
                    .deepThinkPhase(metadata.getDeepThinkPhase())
                    .planStepId(metadata.getPlanStepId())
                    .agentTrace(metadata.getAgentTrace());
        }
        return builder.build();
    }

    public void updateDTOFromRequest(ChatMessageDTO dto, UpdateChatMessageRequest request) {
        Assert.notNull(dto, "ChatMessageDTO cannot be null");
        Assert.notNull(request, "UpdateChatMessageRequest cannot be null");

        if (request.getContent() != null) {
            dto.setContent(request.getContent());
        }
        if (request.getMetadata() != null) {
            dto.setMetadata(request.getMetadata());
        }
    }
}
