package com.yulong.chatagent.support.persistence.adapter.intent;

import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;
import com.yulong.chatagent.support.persistence.mapper.IntentKnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis-backed repository for intent-node knowledge-base bindings.
 */
@Repository
public class MyBatisIntentKnowledgeBaseRepository implements IntentKnowledgeBaseRepository {

    private final IntentKnowledgeBaseMapper intentKnowledgeBaseMapper;

    public MyBatisIntentKnowledgeBaseRepository(IntentKnowledgeBaseMapper intentKnowledgeBaseMapper) {
        this.intentKnowledgeBaseMapper = intentKnowledgeBaseMapper;
    }

    @Override
    public List<IntentKnowledgeBaseDTO> findByIntentNodeIds(List<String> intentNodeIds) {
        if (intentNodeIds == null || intentNodeIds.isEmpty()) {
            return List.of();
        }
        return intentKnowledgeBaseMapper.selectByIntentNodeIds(intentNodeIds);
    }

    @Override
    public boolean save(IntentKnowledgeBaseDTO binding) {
        return intentKnowledgeBaseMapper.insert(binding) > 0;
    }

    @Override
    public boolean saveAll(List<IntentKnowledgeBaseDTO> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return true;
        }
        return intentKnowledgeBaseMapper.batchInsert(bindings) == bindings.size();
    }

    @Override
    public boolean deleteByIntentNodeIds(List<String> intentNodeIds) {
        if (intentNodeIds == null || intentNodeIds.isEmpty()) {
            return true;
        }
        return intentKnowledgeBaseMapper.deleteByIntentNodeIds(intentNodeIds) >= 0;
    }

    @Override
    public boolean deleteByKnowledgeBaseId(String knowledgeBaseId) {
        return intentKnowledgeBaseMapper.deleteByKnowledgeBaseId(knowledgeBaseId) >= 0;
    }
}
