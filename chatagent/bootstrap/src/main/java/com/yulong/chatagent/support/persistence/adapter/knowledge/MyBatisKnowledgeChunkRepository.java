package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.persistence.entity.KnowledgeChunk;
import com.yulong.chatagent.support.persistence.mapper.KnowledgeChunkMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed implementation of the knowledge-chunk repository port.
 */
@Repository
public class MyBatisKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    public MyBatisKnowledgeChunkRepository(KnowledgeChunkMapper knowledgeChunkMapper) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
    }

    @Override
    public List<KnowledgeChunkDTO> findByKnowledgeDocumentId(String knowledgeDocumentId) {
        List<KnowledgeChunkDTO> result = new ArrayList<>();
        for (KnowledgeChunk entity : knowledgeChunkMapper.selectByKnowledgeDocumentId(knowledgeDocumentId)) {
            result.add(toDTO(entity));
        }
        return result;
    }

    @Override
    public void saveAll(List<KnowledgeChunkDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeChunkDTO chunk : chunks) {
            knowledgeChunkMapper.insert(toEntity(chunk));
        }
    }

    @Override
    public void deleteByKnowledgeDocumentId(String knowledgeDocumentId) {
        knowledgeChunkMapper.deleteByKnowledgeDocumentId(knowledgeDocumentId);
    }

    private KnowledgeChunkDTO toDTO(KnowledgeChunk entity) {
        if (entity == null) {
            return null;
        }
        return KnowledgeChunkDTO.builder()
                .id(entity.getId())
                .knowledgeDocumentId(entity.getKnowledgeDocumentId())
                .chunkIndex(entity.getChunkIndex())
                .content(entity.getContent())
                .tokenCount(entity.getTokenCount())
                .metadata(entity.getMetadata())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private KnowledgeChunk toEntity(KnowledgeChunkDTO dto) {
        return KnowledgeChunk.builder()
                .id(dto.getId())
                .knowledgeDocumentId(dto.getKnowledgeDocumentId())
                .chunkIndex(dto.getChunkIndex())
                .content(dto.getContent())
                .tokenCount(dto.getTokenCount())
                .metadata(dto.getMetadata())
                .enabled(dto.getEnabled())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
