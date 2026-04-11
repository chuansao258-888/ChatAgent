package com.yulong.chatagent.support.persistence.adapter.intent;

import com.yulong.chatagent.intent.port.IntentNodeRepository;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import com.yulong.chatagent.support.persistence.mapper.IntentNodeMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis-backed repository for intent tree nodes.
 */
@Repository
public class MyBatisIntentNodeRepository implements IntentNodeRepository {

    private final IntentNodeMapper intentNodeMapper;

    public MyBatisIntentNodeRepository(IntentNodeMapper intentNodeMapper) {
        this.intentNodeMapper = intentNodeMapper;
    }

    @Override
    public List<IntentNodeDTO> findByAgentIdAndVersion(String agentId, int version) {
        return intentNodeMapper.selectByAgentIdAndVersion(agentId, version);
    }

    @Override
    public IntentNodeDTO findById(String id) {
        return intentNodeMapper.selectById(id);
    }

    @Override
    public boolean save(IntentNodeDTO intentNode) {
        return intentNodeMapper.insert(intentNode) > 0;
    }

    @Override
    public boolean saveAll(List<IntentNodeDTO> intentNodes) {
        if (intentNodes == null || intentNodes.isEmpty()) {
            return true;
        }
        return intentNodeMapper.batchInsert(intentNodes) == intentNodes.size();
    }

    @Override
    public boolean update(IntentNodeDTO intentNode) {
        return intentNodeMapper.updateById(intentNode) > 0;
    }

    @Override
    public boolean deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        return intentNodeMapper.deleteByIds(ids) >= 0;
    }

    @Override
    public List<Integer> findPublishedVersions(String agentId) {
        return intentNodeMapper.selectPublishedVersionsByAgentId(agentId);
    }

    @Override
    public Integer findMaxVersion(String agentId) {
        return intentNodeMapper.selectMaxVersionByAgentId(agentId);
    }
}
