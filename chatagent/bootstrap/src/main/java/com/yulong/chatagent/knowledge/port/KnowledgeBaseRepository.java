package com.yulong.chatagent.knowledge.port;

import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;

import java.util.List;

/**
 * Persistence port for knowledge bases.
 */
public interface KnowledgeBaseRepository {

    /**
     * Lists knowledge bases owned by one user.
     *
     * @param userId owner identifier
     * @return owned knowledge bases
     */
    List<KnowledgeBaseDTO> findByUserId(String userId);

    /**
     * Loads one knowledge base by identifier.
     *
     * @param id knowledge base identifier
     * @return matching knowledge base or {@code null}
     */
    KnowledgeBaseDTO findById(String id);

    /**
     * Loads a batch of knowledge bases by identifier.
     *
     * @param ids knowledge base identifiers
     * @return matching knowledge bases
     */
    List<KnowledgeBaseDTO> findByIds(List<String> ids);

    /**
     * Persists a new knowledge base.
     *
     * @param knowledgeBase knowledge base to save
     * @return {@code true} on success
     */
    boolean save(KnowledgeBaseDTO knowledgeBase);

    /**
     * Updates an existing knowledge base.
     *
     * @param knowledgeBase knowledge base to update
     * @return {@code true} on success
     */
    boolean update(KnowledgeBaseDTO knowledgeBase);

    /**
     * Deletes one knowledge base by identifier.
     *
     * @param id knowledge base identifier
     * @return {@code true} on success
     */
    boolean deleteById(String id);
}
