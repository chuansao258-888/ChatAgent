package com.yulong.chatagent.memory.port;

import com.yulong.chatagent.support.dto.MemoryItemDTO;

import java.util.List;
import java.time.LocalDateTime;

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

    MemoryItemDTO findOwnedById(String userId, String id);

    MemoryItemDTO findByUserTypeAndHash(String userId, String type, String contentHash);

    List<MemoryItemDTO> findByUserIdAndStatus(String userId, String status);

    List<MemoryItemDTO> findIndexCandidates(int limit);

    boolean updateIndexStatus(String id, String indexStatus);

    boolean correct(String userId, String id, LocalDateTime expectedUpdatedAt,
                    String type, String content, String contentHash);

    boolean reactivate(String userId, String id);

    boolean archive(String userId, String id);
}
