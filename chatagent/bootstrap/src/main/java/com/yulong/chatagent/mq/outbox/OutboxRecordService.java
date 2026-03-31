package com.yulong.chatagent.mq.outbox;

import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates state transitions for outbox rows so polling and publishing can stay idempotent.
 */
@Service
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class OutboxRecordService {

    private final OutboxRepository outboxRepository;
    private final ChatAgentMqProperties properties;

    public OutboxRecordService(OutboxRepository outboxRepository, ChatAgentMqProperties properties) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
    }

    @Transactional
    public List<MqOutbox> claimBatch(String claimedBy, LocalDateTime now) {
        LocalDateTime staleClaimBefore = now.minusNanos(properties.getOutbox().getClaimTimeoutMs() * 1_000_000L);
        List<MqOutbox> candidates = outboxRepository.selectClaimableBatch(
                properties.getOutbox().getBatchSize(),
                now,
                staleClaimBefore,
                properties.getOutbox().getMaxPublishAttempts()
        );
        List<MqOutbox> claimed = new ArrayList<>();
        for (MqOutbox outbox : candidates) {
            if (outboxRepository.markClaimed(outbox.getId(), claimedBy, now, outbox.getVersion())) {
                outbox.setStatus("CLAIMED");
                outbox.setClaimedBy(claimedBy);
                outbox.setClaimedAt(now);
                outbox.setVersion(outbox.getVersion() + 1);
                claimed.add(outbox);
            }
        }
        return claimed;
    }

    @Transactional
    public void markSent(MqOutbox outbox) {
        if (!outboxRepository.markSent(outbox.getId(), outbox.getVersion())) {
            throw new IllegalStateException("Failed to mark outbox row as SENT: " + outbox.getId());
        }
    }

    @Transactional
    public void markPublishFailure(MqOutbox outbox, String errorMessage, LocalDateTime now) {
        int newRetryCount = outbox.getRetryCount() + 1;
        boolean updated;
        if (newRetryCount >= properties.getOutbox().getMaxPublishAttempts()) {
            updated = outboxRepository.markPermanentlyFailed(
                    outbox.getId(),
                    errorMessage,
                    newRetryCount,
                    outbox.getVersion()
            );
        } else {
            LocalDateTime nextRetryAt = now.plusNanos(properties.getOutbox().getPublishRetryDelayMs() * 1_000_000L);
            updated = outboxRepository.markFailed(
                    outbox.getId(),
                    errorMessage,
                    nextRetryAt,
                    newRetryCount,
                    outbox.getVersion()
            );
        }
        if (!updated) {
            throw new IllegalStateException("Failed to update outbox failure state: " + outbox.getId());
        }
    }

    @Transactional
    public int cleanupOlderSentRows(LocalDateTime cutoff) {
        return outboxRepository.deleteOlderSentRows(cutoff);
    }
}
