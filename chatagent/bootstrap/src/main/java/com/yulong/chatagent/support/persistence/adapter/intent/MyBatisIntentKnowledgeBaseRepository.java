package com.yulong.chatagent.support.persistence.adapter.intent;

import com.yulong.chatagent.intent.port.IntentKnowledgeBaseRepository;
import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;
import com.yulong.chatagent.support.persistence.entity.IntentKnowledgeBase;
import com.yulong.chatagent.support.persistence.mapper.IntentKnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
        List<IntentKnowledgeBaseDTO> result = new ArrayList<>();
        for (IntentKnowledgeBase binding : intentKnowledgeBaseMapper.selectByIntentNodeIds(intentNodeIds)) {
            result.add(toDTO(binding));
        }
        return result;
    }

    @Override
    public boolean save(IntentKnowledgeBaseDTO binding) {
        IntentKnowledgeBase entity = toEntity(binding);
        boolean saved = intentKnowledgeBaseMapper.insert(entity) > 0;
        if (saved && binding != null) {
            binding.setId(entity.getId());
        }
        return saved;
    }

    @Override
    public boolean saveAll(List<IntentKnowledgeBaseDTO> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return true;
        }
        List<IntentKnowledgeBase> entities = new ArrayList<>();
        for (IntentKnowledgeBaseDTO binding : bindings) {
            entities.add(toEntity(binding));
        }
        return intentKnowledgeBaseMapper.batchInsert(entities) == entities.size();
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

    private IntentKnowledgeBaseDTO toDTO(IntentKnowledgeBase entity) {
        if (entity == null) {
            return null;
        }
        return IntentKnowledgeBaseDTO.builder()
                .id(entity.getId())
                .intentNodeId(entity.getIntentNodeId())
                .knowledgeBaseId(entity.getKnowledgeBaseId())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private IntentKnowledgeBase toEntity(IntentKnowledgeBaseDTO dto) {
        if (dto == null) {
            return null;
        }
        return IntentKnowledgeBase.builder()
                .id(dto.getId())
                .intentNodeId(dto.getIntentNodeId())
                .knowledgeBaseId(dto.getKnowledgeBaseId())
                .createdAt(dto.getCreatedAt())
                .build();
    }
}
