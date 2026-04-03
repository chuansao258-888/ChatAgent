package com.yulong.chatagent.mq.outbox;

import jakarta.annotation.PreDestroy;
import com.yulong.chatagent.mq.config.ChatAgentMqProperties;
import com.yulong.chatagent.support.persistence.entity.MqOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates state transitions for outbox rows so polling and publishing can stay idempotent.
 */
@Service
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
@Slf4j
public class OutboxRecordService {

    private final OutboxRepository outboxRepository;
    private final ChatAgentMqProperties properties;
    private final ThreadPoolExecutor discardExecutor;

    public OutboxRecordService(OutboxRepository outboxRepository, ChatAgentMqProperties properties) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.discardExecutor = new ThreadPoolExecutor(
                1,
                1,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                runnable -> {
                    Thread thread = new Thread(runnable, "mq-outbox-discard");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
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
    public boolean markSent(MqOutbox outbox) {
        return outboxRepository.markSent(outbox.getId(), outbox.getVersion());
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

    public void scheduleDiscardedConflict(MqOutbox outbox) {
        discardExecutor.execute(() -> {
            try {
                discardConflict(outbox);
            } catch (Exception e) {
                log.error("Failed to discard conflicted outbox row asynchronously: id={}", outbox.getId(), e);
            }
        });
    }

    @PreDestroy
    void shutdownDiscardExecutor() {
        discardExecutor.shutdownNow();
    }

    private void discardConflict(MqOutbox outbox) {
        MqOutbox current = outboxRepository.findById(outbox.getId());
        if (current == null) {
            throw new IllegalStateException("Outbox row disappeared after publish attempt: " + outbox.getId());
        }
        if ("SENT".equals(current.getStatus()) || "DISCARDED".equals(current.getStatus())) {
            return;
        }
        String lastError = "Discarded after markSent conflict; observed status=" + current.getStatus();
        boolean discarded = outboxRepository.markDiscarded(current.getId(), lastError, current.getVersion());
        if (!discarded) {
            throw new IllegalStateException("Failed to discard conflicted outbox row: " + current.getId());
        }
    }
}
