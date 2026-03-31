package com.yulong.chatagent.support.persistence.adapter.mq;

import com.yulong.chatagent.mq.outbox.OutboxRepository;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import com.yulong.chatagent.support.persistence.mapper.MqOutboxMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-backed implementation of the outbox repository port.
 */
@Repository
public class MyBatisOutboxRepository implements OutboxRepository {

    private final MqOutboxMapper mqOutboxMapper;

    public MyBatisOutboxRepository(MqOutboxMapper mqOutboxMapper) {
        this.mqOutboxMapper = mqOutboxMapper;
    }

    @Override
    public void insert(MqOutbox outbox) {
        mqOutboxMapper.insert(outbox);
    }

    @Override
    public List<MqOutbox> selectClaimableBatch(int limit,
                                               LocalDateTime now,
                                               LocalDateTime staleClaimBefore,
                                               int maxAttempts) {
        return mqOutboxMapper.selectClaimableBatch(limit, now, staleClaimBefore, maxAttempts);
    }

    @Override
    public boolean markClaimed(String id, String claimedBy, LocalDateTime claimedAt, int expectedVersion) {
        return mqOutboxMapper.markClaimed(id, claimedBy, claimedAt, expectedVersion) > 0;
    }

    @Override
    public boolean markSent(String id, int expectedVersion) {
        return mqOutboxMapper.markSent(id, expectedVersion) > 0;
    }

    @Override
    public boolean markFailed(String id, String lastError, LocalDateTime nextRetryAt,
                              int newRetryCount, int expectedVersion) {
        return mqOutboxMapper.markFailed(id, lastError, nextRetryAt, newRetryCount, expectedVersion) > 0;
    }

    @Override
    public boolean markPermanentlyFailed(String id, String lastError, int newRetryCount, int expectedVersion) {
        return mqOutboxMapper.markPermanentlyFailed(id, lastError, newRetryCount, expectedVersion) > 0;
    }

    @Override
    public int deleteOlderSentRows(LocalDateTime cutoff) {
        return mqOutboxMapper.deleteOlderSentRows(cutoff);
    }

    @Override
    public int countByStatus(String status) {
        return mqOutboxMapper.countByStatus(status);
    }

    @Override
    public List<MqOutbox> findRecent(String eventId, String idempotencyKey, String status, int limit) {
        return mqOutboxMapper.findRecent(eventId, idempotencyKey, status, limit);
    }
}
