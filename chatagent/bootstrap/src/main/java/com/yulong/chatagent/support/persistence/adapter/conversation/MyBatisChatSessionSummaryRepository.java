package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.persistence.entity.ChatSessionSummary;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummaryMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-backed repository for rolling chat-session summaries.
 */
@Repository
public class MyBatisChatSessionSummaryRepository implements ChatSessionSummaryRepository {

    private static final TypeReference<Map<String, List<String>>> ANCHORED_ENTITY_MAP_TYPE = new TypeReference<>() {
    };

    private final ChatSessionSummaryMapper chatSessionSummaryMapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatSessionSummaryRepository(ChatSessionSummaryMapper chatSessionSummaryMapper,
                                               ObjectMapper objectMapper) {
        this.chatSessionSummaryMapper = chatSessionSummaryMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatSessionSummaryDTO findBySessionId(String sessionId) {
        return toDTO(chatSessionSummaryMapper.selectBySessionId(sessionId));
    }

    @Override
    public boolean saveOrUpdate(ChatSessionSummaryDTO summary) {
        ChatSessionSummaryDTO existing = findBySessionId(summary.getSessionId());
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            if (summary.getLastSeqNo() == null) {
                summary.setLastSeqNo(0L);
            }
            if (summary.getVersion() == null) {
                summary.setVersion(0);
            }
            summary.setCreatedAt(now);
            summary.setUpdatedAt(now);
            return chatSessionSummaryMapper.insert(toEntity(summary)) > 0;
        }

        summary.setVersion(existing.getVersion());
        summary.setCreatedAt(existing.getCreatedAt());
        summary.setUpdatedAt(LocalDateTime.now());
        boolean updated = chatSessionSummaryMapper.updateBySessionIdAndVersion(toEntity(summary)) > 0;
        if (updated) {
            summary.setVersion(existing.getVersion() + 1);
        }
        return updated;
    }

    @Override
    public boolean deleteBySessionId(String sessionId) {
        return chatSessionSummaryMapper.deleteBySessionId(sessionId) > 0;
    }

    private ChatSessionSummaryDTO toDTO(ChatSessionSummary summary) {
        if (summary == null) {
            return null;
        }
        try {
            return ChatSessionSummaryDTO.builder()
                    .sessionId(summary.getSessionId())
                    .lastSeqNo(summary.getLastSeqNo())
                    .summary(summary.getSummary())
                    .anchoredEntities(readAnchoredEntities(summary.getAnchoredEntities()))
                    .version(summary.getVersion())
                    .createdAt(summary.getCreatedAt())
                    .updatedAt(summary.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize chat session summary", e);
        }
    }

    private ChatSessionSummary toEntity(ChatSessionSummaryDTO summary) {
        if (summary == null) {
            return null;
        }
        try {
            return ChatSessionSummary.builder()
                    .sessionId(summary.getSessionId())
                    .lastSeqNo(summary.getLastSeqNo())
                    .summary(summary.getSummary())
                    .anchoredEntities(writeAnchoredEntities(summary.getAnchoredEntities()))
                    .version(summary.getVersion())
                    .createdAt(summary.getCreatedAt())
                    .updatedAt(summary.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat session summary", e);
        }
    }

    private Map<String, List<String>> readAnchoredEntities(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, ANCHORED_ENTITY_MAP_TYPE);
    }

    private String writeAnchoredEntities(Map<String, List<String>> anchoredEntities) throws JsonProcessingException {
        return objectMapper.writeValueAsString(anchoredEntities == null ? Map.of() : anchoredEntities);
    }
}
