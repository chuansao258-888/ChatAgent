package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import com.yulong.chatagent.support.persistence.entity.KnowledgeDocument;
import com.yulong.chatagent.support.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed implementation of the knowledge-document repository port.
 */
@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public MyBatisKnowledgeDocumentRepository(KnowledgeDocumentMapper knowledgeDocumentMapper) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
    }

    @Override
    public List<KnowledgeDocumentDTO> findByKnowledgeBaseId(String knowledgeBaseId) {
        List<KnowledgeDocumentDTO> result = new ArrayList<>();
        for (KnowledgeDocument entity : knowledgeDocumentMapper.selectByKnowledgeBaseId(knowledgeBaseId)) {
            result.add(toDTO(entity));
        }
        return result;
    }

    @Override
    public KnowledgeDocumentDTO findById(String id) {
        return toDTO(knowledgeDocumentMapper.selectById(id));
    }

    @Override
    public boolean save(KnowledgeDocumentDTO knowledgeDocument) {
        return knowledgeDocumentMapper.insert(toEntity(knowledgeDocument)) > 0;
    }

    @Override
    public boolean update(KnowledgeDocumentDTO knowledgeDocument) {
        return knowledgeDocumentMapper.updateById(toEntity(knowledgeDocument)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return knowledgeDocumentMapper.deleteById(id) > 0;
    }

    private KnowledgeDocumentDTO toDTO(KnowledgeDocument entity) {
        if (entity == null) {
            return null;
        }
        return KnowledgeDocumentDTO.builder()
                .id(entity.getId())
                .knowledgeBaseId(entity.getKnowledgeBaseId())
                .filename(entity.getFilename())
                .originalFilename(entity.getOriginalFilename())
                .mimeType(entity.getMimeType())
                .sizeBytes(entity.getSizeBytes())
                .storagePath(entity.getStoragePath())
                .parseStatus(entity.getParseStatus())
                .contentHash(entity.getContentHash())
                .failedReason(entity.getFailedReason())
                .indexedAt(entity.getIndexedAt())
                .retryCount(entity.getRetryCount())
                .metadata(entity.getMetadata())
                .deleted(entity.getDeleted())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private KnowledgeDocument toEntity(KnowledgeDocumentDTO dto) {
        return KnowledgeDocument.builder()
                .id(dto.getId())
                .knowledgeBaseId(dto.getKnowledgeBaseId())
                .filename(dto.getFilename())
                .originalFilename(dto.getOriginalFilename())
                .mimeType(dto.getMimeType())
                .sizeBytes(dto.getSizeBytes())
                .storagePath(dto.getStoragePath())
                .parseStatus(dto.getParseStatus())
                .contentHash(dto.getContentHash())
                .failedReason(dto.getFailedReason())
                .indexedAt(dto.getIndexedAt())
                .retryCount(dto.getRetryCount())
                .metadata(dto.getMetadata())
                .deleted(dto.getDeleted())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
