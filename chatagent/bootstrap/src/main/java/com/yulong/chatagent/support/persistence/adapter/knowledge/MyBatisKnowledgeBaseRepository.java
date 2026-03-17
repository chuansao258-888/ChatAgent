package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.persistence.converter.KnowledgeBaseConverter;
import com.yulong.chatagent.support.persistence.entity.KnowledgeBase;
import com.yulong.chatagent.support.persistence.mapper.KnowledgeBaseMapper;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;

    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper knowledgeBaseMapper,
                                          KnowledgeBaseConverter knowledgeBaseConverter) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
    }

    @Override
    public List<KnowledgeBaseDTO> findAll() {
        return toDTOList(knowledgeBaseMapper.selectAll());
    }

    @Override
    public KnowledgeBaseDTO findById(String id) {
        return toDTO(knowledgeBaseMapper.selectById(id));
    }

    @Override
    public List<KnowledgeBaseDTO> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return toDTOList(knowledgeBaseMapper.selectByIdBatch(ids));
    }

    @Override
    public boolean save(KnowledgeBaseDTO knowledgeBase) {
        KnowledgeBase entity = toEntity(knowledgeBase);
        boolean saved = knowledgeBaseMapper.insert(entity) > 0;
        if (saved) {
            knowledgeBase.setId(entity.getId());
        }
        return saved;
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
        try {
            return knowledgeBaseConverter.toDTO(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize knowledge base", e);
        }
    }

    private KnowledgeBase toEntity(KnowledgeBaseDTO dto) {
        try {
            return knowledgeBaseConverter.toEntity(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize knowledge base", e);
        }
    }
}
