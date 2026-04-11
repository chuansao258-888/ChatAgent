package com.yulong.chatagent.support.persistence.adapter.knowledge;

import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
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
        return new ArrayList<>(knowledgeChunkMapper.selectByKnowledgeDocumentId(knowledgeDocumentId));
    }

    @Override
    public void saveAll(List<KnowledgeChunkDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeChunkDTO chunk : chunks) {
            knowledgeChunkMapper.insert(chunk);
        }
    }

    @Override
    public void deleteByKnowledgeDocumentId(String knowledgeDocumentId) {
        knowledgeChunkMapper.deleteByKnowledgeDocumentId(knowledgeDocumentId);
    }
}
