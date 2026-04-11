package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
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
        return new ArrayList<>(knowledgeBaseMapper.selectAll());
    }

    @Override
    public List<KnowledgeBaseDTO> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(knowledgeBaseMapper.selectByIds(ids));
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
        return knowledgeBaseMapper.selectById(id);
    }

    @Override
    public boolean save(KnowledgeBaseDTO knowledgeBase) {
        return knowledgeBaseMapper.insert(knowledgeBase) > 0;
    }

    @Override
    public boolean update(KnowledgeBaseDTO knowledgeBase) {
        return knowledgeBaseMapper.updateById(knowledgeBase) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return knowledgeBaseMapper.deleteById(id) > 0;
    }
}
