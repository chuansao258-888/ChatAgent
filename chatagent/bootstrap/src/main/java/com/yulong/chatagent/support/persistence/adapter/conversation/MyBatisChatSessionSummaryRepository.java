package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummaryMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-backed repository for rolling chat-session summaries (V2 schema).
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
        ChatSessionSummaryDTO dto = chatSessionSummaryMapper.selectBySessionId(sessionId);
        if (dto != null) {
            dto.setAnchoredEntities(readAnchoredEntities(dto.getAnchoredEntitiesJson()));
        }
        return dto;
    }

    @Override
    public boolean saveOrUpdate(ChatSessionSummaryDTO summary) {
        ChatSessionSummaryDTO existing = findBySessionId(summary.getSessionId());
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            if (summary.getSummarizedUntilSeqNo() == null) {
                summary.setSummarizedUntilSeqNo(0L);
            }
            if (summary.getVersion() == null) {
                summary.setVersion(0);
            }
            if (summary.getSegmentCount() == null) {
                summary.setSegmentCount(0);
            }
            if (summary.getConsecutiveFailures() == null) {
                summary.setConsecutiveFailures(0);
            }
            summary.setCreatedAt(now);
            summary.setUpdatedAt(now);
            summary.setAnchoredEntitiesJson(writeAnchoredEntities(summary.getAnchoredEntities()));
            summary.setStructuredSummaryJson(defaultJson(summary.getStructuredSummaryJson()));
            return chatSessionSummaryMapper.insert(summary) > 0;
        }

        summary.setVersion(existing.getVersion());
        summary.setCreatedAt(existing.getCreatedAt());
        summary.setUpdatedAt(LocalDateTime.now());
        summary.setAnchoredEntitiesJson(writeAnchoredEntities(summary.getAnchoredEntities()));
        summary.setStructuredSummaryJson(defaultJson(summary.getStructuredSummaryJson()));
        if (summary.getSegmentCount() == null) {
            summary.setSegmentCount(existing.getSegmentCount() == null ? 0 : existing.getSegmentCount());
        }
        if (summary.getConsecutiveFailures() == null) {
            summary.setConsecutiveFailures(existing.getConsecutiveFailures() == null ? 0 : existing.getConsecutiveFailures());
        }
        boolean updated = chatSessionSummaryMapper.updateBySessionIdAndVersion(summary) > 0;
        if (updated) {
            summary.setVersion(existing.getVersion() + 1);
        }
        return updated;
    }

    @Override
    public boolean deleteBySessionId(String sessionId) {
        return chatSessionSummaryMapper.deleteBySessionId(sessionId) > 0;
    }

    private Map<String, List<String>> readAnchoredEntities(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, ANCHORED_ENTITY_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize chat session summary", e);
        }
    }

    private String writeAnchoredEntities(Map<String, List<String>> anchoredEntities) {
        try {
            return objectMapper.writeValueAsString(anchoredEntities == null ? Map.of() : anchoredEntities);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat session summary", e);
        }
    }

    private static String defaultJson(String json) {
        return (json == null || json.isBlank()) ? "{}" : json;
    }
}
