package com.yulong.chatagent.knowledge.converter;

import com.yulong.chatagent.knowledge.model.vo.KnowledgeBaseVO;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Converts knowledge-base DTOs into response view objects.
 */
@Component
public class KnowledgeBaseConverter {

    public KnowledgeBaseVO toVO(KnowledgeBaseDTO dto) {
        Assert.notNull(dto, "KnowledgeBaseDTO cannot be null");
        return KnowledgeBaseVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .visibility(dto.getVisibility())
                .status(dto.getStatus())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
