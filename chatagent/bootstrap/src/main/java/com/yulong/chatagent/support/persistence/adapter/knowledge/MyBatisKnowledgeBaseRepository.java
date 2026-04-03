package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import com.yulong.chatagent.support.persistence.entity.KnowledgeBase;
import com.yulong.chatagent.support.persistence.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed implementation of the knowledge-base repository port.
 */
@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public List<KnowledgeBaseDTO> findAll() {
        return toDTOList(knowledgeBaseMapper.selectAll());
    }

    @Override
    public List<KnowledgeBaseDTO> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return toDTOList(knowledgeBaseMapper.selectByIds(ids));
    }

    @Override
    public List<String> filterActiveIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseMapper.selectActiveIds(ids);
    }

    @Override
    public KnowledgeBaseDTO findById(String id) {
        return toDTO(knowledgeBaseMapper.selectById(id));
    }

    @Override
    public boolean save(KnowledgeBaseDTO knowledgeBase) {
        return knowledgeBaseMapper.insert(toEntity(knowledgeBase)) > 0;
    }

    @Override
    public boolean update(KnowledgeBaseDTO knowledgeBase) {
        return knowledgeBaseMapper.updateById(toEntity(knowledgeBase)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return knowledgeBaseMapper.deleteById(id) > 0;
    }

    private List<KnowledgeBaseDTO> toDTOList(List<KnowledgeBase> entities) {
        List<KnowledgeBaseDTO> result = new ArrayList<>();
        for (KnowledgeBase entity : entities) {
            result.add(toDTO(entity));
        }
        return result;
    }

    private KnowledgeBaseDTO toDTO(KnowledgeBase entity) {
        if (entity == null) {
            return null;
        }
        return KnowledgeBaseDTO.builder()
                .id(entity.getId())
                .createdBy(entity.getCreatedBy())
                .name(entity.getName())
                .description(entity.getDescription())
                .visibility(entity.getVisibility())
                .status(entity.getStatus())
                .metadata(entity.getMetadata())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private KnowledgeBase toEntity(KnowledgeBaseDTO dto) {
        return KnowledgeBase.builder()
                .id(dto.getId())
                .createdBy(dto.getCreatedBy())
                .name(dto.getName())
                .description(dto.getDescription())
                .visibility(dto.getVisibility())
                .status(dto.getStatus())
                .metadata(dto.getMetadata())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
