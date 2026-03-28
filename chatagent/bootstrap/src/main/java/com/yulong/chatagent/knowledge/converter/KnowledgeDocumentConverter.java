package com.yulong.chatagent.knowledge.converter;

import com.yulong.chatagent.knowledge.model.vo.KnowledgeDocumentVO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Converts knowledge-document DTOs into response view objects.
 */
@Component
public class KnowledgeDocumentConverter {

    public KnowledgeDocumentVO toVO(KnowledgeDocumentDTO dto) {
        Assert.notNull(dto, "KnowledgeDocumentDTO cannot be null");
        return KnowledgeDocumentVO.builder()
                .id(dto.getId())
                .knowledgeBaseId(dto.getKnowledgeBaseId())
                .filename(dto.getFilename())
                .originalFilename(dto.getOriginalFilename())
                .mimeType(dto.getMimeType())
                .sizeBytes(dto.getSizeBytes())
                .parseStatus(dto.getParseStatus())
                .deleted(Boolean.TRUE.equals(dto.getDeleted()))
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
