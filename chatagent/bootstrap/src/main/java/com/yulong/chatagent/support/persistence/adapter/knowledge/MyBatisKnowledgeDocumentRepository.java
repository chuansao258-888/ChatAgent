package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
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
        return new ArrayList<>(knowledgeDocumentMapper.selectByKnowledgeBaseId(knowledgeBaseId));
    }

    @Override
    public KnowledgeDocumentDTO findById(String id) {
        return knowledgeDocumentMapper.selectById(id);
    }

    @Override
    public boolean save(KnowledgeDocumentDTO knowledgeDocument) {
        return knowledgeDocumentMapper.insert(knowledgeDocument) > 0;
    }

    @Override
    public boolean update(KnowledgeDocumentDTO knowledgeDocument) {
        return knowledgeDocumentMapper.updateById(knowledgeDocument) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return knowledgeDocumentMapper.deleteById(id) > 0;
    }
}
