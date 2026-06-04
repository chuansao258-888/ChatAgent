package com.yulong.chatagent.memory.port;

import com.yulong.chatagent.support.dto.MemoryItemDTO;

import java.util.List;

/**
 * Persistence port for long-term memory items.
 */
public interface MemoryItemRepository {

    /**
     * Atomic upsert using PostgreSQL ON CONFLICT.
     * On duplicate (user_id, type, content_hash), updates tags, source, and updated_at.
     * Returns the persisted DTO (with id populated).
     */
    MemoryItemDTO upsert(MemoryItemDTO item);

    MemoryItemDTO findById(String id);

    List<MemoryItemDTO> findByUserIdAndStatus(String userId, String status);

    boolean updateIndexStatus(String id, String indexStatus);

    boolean updateStatus(String id, String status);
}
