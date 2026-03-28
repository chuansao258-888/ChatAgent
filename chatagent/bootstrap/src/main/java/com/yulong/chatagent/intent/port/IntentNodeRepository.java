package com.yulong.chatagent.intent.port;

import com.yulong.chatagent.support.dto.IntentNodeDTO;

import java.util.List;

/**
 * Persistence port for intent tree nodes.
 */
public interface IntentNodeRepository {

    List<IntentNodeDTO> findByAgentIdAndVersion(String agentId, int version);

    IntentNodeDTO findById(String id);

    boolean save(IntentNodeDTO intentNode);

    boolean saveAll(List<IntentNodeDTO> intentNodes);

    boolean update(IntentNodeDTO intentNode);

    boolean deleteByIds(List<String> ids);

    List<Integer> findPublishedVersions(String agentId);

    Integer findMaxVersion(String agentId);
}
