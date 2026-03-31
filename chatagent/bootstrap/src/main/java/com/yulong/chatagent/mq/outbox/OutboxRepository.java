package com.yulong.chatagent.mq.outbox;

import com.yulong.chatagent.support.persistence.entity.MqOutbox;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Port interface for the transactional outbox persistence.
 */
public interface OutboxRepository {

    void insert(MqOutbox outbox);

    List<MqOutbox> selectClaimableBatch(int limit,
                                        LocalDateTime now,
                                        LocalDateTime staleClaimBefore,
                                        int maxAttempts);

    boolean markClaimed(String id, String claimedBy, LocalDateTime claimedAt, int expectedVersion);

    boolean markSent(String id, int expectedVersion);

    boolean markFailed(String id, String lastError, LocalDateTime nextRetryAt, int newRetryCount, int expectedVersion);

    boolean markPermanentlyFailed(String id, String lastError, int newRetryCount, int expectedVersion);

    int deleteOlderSentRows(LocalDateTime cutoff);

    int countByStatus(String status);

    List<MqOutbox> findRecent(String eventId, String idempotencyKey, String status, int limit);
}
