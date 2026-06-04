package com.yulong.chatagent.memory.port;

import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;

/**
 * Persistence port for memory extraction idempotency logs.
 */
public interface MemoryExtractionLogRepository {

    /**
     * Checks whether a given range has already been extracted.
     */
    MemoryExtractionLogDTO findByRange(String sessionId, long seqStartNo, long seqEndNo);

    /**
     * Inserts a new extraction log entry.
     */
    MemoryExtractionLogDTO insert(MemoryExtractionLogDTO log);

    /**
     * Updates the status (and optional error message) of an existing extraction log.
     */
    boolean updateStatus(String id, String status, String errorMessage);
}
