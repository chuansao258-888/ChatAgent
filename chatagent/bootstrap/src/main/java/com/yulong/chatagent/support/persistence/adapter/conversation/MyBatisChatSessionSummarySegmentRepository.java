package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummarySegmentMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-backed repository for L2 summary segments.
 */
@Repository
public class MyBatisChatSessionSummarySegmentRepository implements ChatSessionSummarySegmentRepository {

    private static final TypeReference<Map<String, List<String>>> ANCHORED_ENTITY_MAP_TYPE = new TypeReference<>() {
    };

    private final ChatSessionSummarySegmentMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatSessionSummarySegmentRepository(ChatSessionSummarySegmentMapper mapper,
                                                      ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean insert(ChatSessionSummarySegmentDTO segment) {
        LocalDateTime now = LocalDateTime.now();
        segment.setAnchoredEntitiesJson(writeAnchoredEntities(segment.getAnchoredEntities()));
        segment.setStructuredSummaryJson(defaultJson(segment.getStructuredSummaryJson()));
        if (segment.getStatus() == null) {
            segment.setStatus("active");
        }
        if (segment.getCreatedAt() == null) {
            segment.setCreatedAt(now);
        }
        if (segment.getUpdatedAt() == null) {
            segment.setUpdatedAt(now);
        }
        return mapper.insert(segment) > 0;
    }

    @Override
    public List<ChatSessionSummarySegmentDTO> findActiveBySessionId(String sessionId) {
        List<ChatSessionSummarySegmentDTO> segments = mapper.selectActiveBySessionId(sessionId);
        segments.forEach(this::hydrateAnchoredEntities);
        return segments;
    }

    @Override
    public List<ChatSessionSummarySegmentDTO> findActiveBySessionIdOrdered(String sessionId) {
        List<ChatSessionSummarySegmentDTO> segments = mapper.selectActiveBySessionIdOrdered(sessionId);
        segments.forEach(this::hydrateAnchoredEntities);
        return segments;
    }

    @Override
    public boolean deleteBySessionId(String sessionId) {
        return mapper.deleteBySessionId(sessionId) > 0;
    }

    private void hydrateAnchoredEntities(ChatSessionSummarySegmentDTO dto) {
        if (dto != null) {
            dto.setAnchoredEntities(readAnchoredEntities(dto.getAnchoredEntitiesJson()));
        }
    }

    private Map<String, List<String>> readAnchoredEntities(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, ANCHORED_ENTITY_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize segment anchored entities", e);
        }
    }

    private String writeAnchoredEntities(Map<String, List<String>> anchoredEntities) {
        try {
            return objectMapper.writeValueAsString(anchoredEntities == null ? Map.of() : anchoredEntities);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize segment anchored entities", e);
        }
    }

    private static String defaultJson(String json) {
        return (json == null || json.isBlank()) ? "{}" : json;
    }
}
