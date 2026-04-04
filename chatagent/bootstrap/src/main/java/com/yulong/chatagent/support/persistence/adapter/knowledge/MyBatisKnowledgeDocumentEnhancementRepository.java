package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentEnhancementRepository;
import com.yulong.chatagent.support.dto.KnowledgeDocumentEnhancementDTO;
import com.yulong.chatagent.support.persistence.entity.KnowledgeDocumentEnhancement;
import com.yulong.chatagent.support.persistence.mapper.KnowledgeDocumentEnhancementMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-backed repository for document-level enhancement signals.
 */
@Repository
public class MyBatisKnowledgeDocumentEnhancementRepository implements KnowledgeDocumentEnhancementRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final KnowledgeDocumentEnhancementMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisKnowledgeDocumentEnhancementRepository(KnowledgeDocumentEnhancementMapper mapper,
                                                         ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeDocumentEnhancementDTO findByKnowledgeDocumentId(String knowledgeDocumentId) {
        return toDto(mapper.selectByKnowledgeDocumentId(knowledgeDocumentId));
    }

    @Override
    public List<KnowledgeDocumentEnhancementDTO> findByKnowledgeDocumentIds(List<String> knowledgeDocumentIds) {
        List<KnowledgeDocumentEnhancementDTO> results = new ArrayList<>();
        for (KnowledgeDocumentEnhancement entity : mapper.selectByKnowledgeDocumentIds(knowledgeDocumentIds)) {
            results.add(toDto(entity));
        }
        return results;
    }

    @Override
    public boolean saveOrUpdate(KnowledgeDocumentEnhancementDTO enhancement) {
        return mapper.upsert(toEntity(enhancement)) > 0;
    }

    @Override
    public boolean deleteByKnowledgeDocumentId(String knowledgeDocumentId) {
        return mapper.deleteByKnowledgeDocumentId(knowledgeDocumentId) > 0;
    }

    private KnowledgeDocumentEnhancementDTO toDto(KnowledgeDocumentEnhancement entity) {
        if (entity == null) {
            return null;
        }
        try {
            return KnowledgeDocumentEnhancementDTO.builder()
                    .knowledgeDocumentId(entity.getKnowledgeDocumentId())
                    .enhancerCacheKey(entity.getEnhancerCacheKey())
                    .keywords(readStringList(entity.getKeywords()))
                    .questions(readStringList(entity.getQuestions()))
                    .metadata(readMetadata(entity.getMetadata()))
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize knowledge document enhancement", e);
        }
    }

    private KnowledgeDocumentEnhancement toEntity(KnowledgeDocumentEnhancementDTO dto) {
        try {
            return KnowledgeDocumentEnhancement.builder()
                    .knowledgeDocumentId(dto.getKnowledgeDocumentId())
                    .enhancerCacheKey(dto.getEnhancerCacheKey())
                    .keywords(objectMapper.writeValueAsString(dto.getKeywords() == null ? List.of() : dto.getKeywords()))
                    .questions(objectMapper.writeValueAsString(dto.getQuestions() == null ? List.of() : dto.getQuestions()))
                    .metadata(objectMapper.writeValueAsString(dto.getMetadata() == null ? Map.of() : dto.getMetadata()))
                    .createdAt(dto.getCreatedAt())
                    .updatedAt(dto.getUpdatedAt())
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize knowledge document enhancement", e);
        }
    }

    private List<String> readStringList(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, STRING_LIST_TYPE);
    }

    private Map<String, Object> readMetadata(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, MAP_TYPE);
    }
}
