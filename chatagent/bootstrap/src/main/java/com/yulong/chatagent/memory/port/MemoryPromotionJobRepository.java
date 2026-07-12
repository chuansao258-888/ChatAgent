package com.yulong.chatagent.memory.port;

import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;

import java.time.LocalDateTime;

/** Persistence boundary for durable L3 promotion work. */
public interface MemoryPromotionJobRepository {

    boolean insertPendingForSession(String sessionId, long seqStartNo, long seqEndNo);

    MemoryPromotionJobDTO claimNextDue(LocalDateTime now);

    boolean markCompleted(String id);

    boolean markRetry(String id, LocalDateTime nextAttemptAt, String lastError);

    boolean markFailed(String id, String lastError);

    int reclaimStale(LocalDateTime processingStartedBefore);

    long countBacklog();
}
