package com.yulong.chatagent.intent.port;

import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;

import java.util.List;

/**
 * Persistence port for intent-node knowledge-base bindings.
 */
public interface IntentKnowledgeBaseRepository {

    List<IntentKnowledgeBaseDTO> findByIntentNodeIds(List<String> intentNodeIds);

    boolean save(IntentKnowledgeBaseDTO binding);

    boolean saveAll(List<IntentKnowledgeBaseDTO> bindings);

    boolean deleteByIntentNodeIds(List<String> intentNodeIds);
}
