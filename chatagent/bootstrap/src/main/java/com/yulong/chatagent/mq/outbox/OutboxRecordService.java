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
 * Outbox 状态机服务。
 *
 * OutboxPollingPublisher 只负责“扫描 + 发布”，具体状态变更集中放在这里：
 * PENDING -> CLAIMED -> SENT
 * PENDING/CLAIMED -> PENDING(下次重试)
 * PENDING/CLAIMED -> FAILED(发布重试耗尽)
 * CLAIMED -> DISCARDED(已发布成功但 markSent 冲突的保护分支)
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
        // CLAIMED 太久没更新，通常表示扫描器进程挂了；超过 claimTimeout 后允许其他实例接手。
        LocalDateTime staleClaimBefore = now.minusNanos(properties.getOutbox().getClaimTimeoutMs() * 1_000_000L);
        List<MqOutbox> candidates = outboxRepository.selectClaimableBatch(
                properties.getOutbox().getBatchSize(),
                now,
                staleClaimBefore,
                properties.getOutbox().getMaxPublishAttempts()
        );
        List<MqOutbox> claimed = new ArrayList<>();
        for (MqOutbox outbox : candidates) {
            // markClaimed 带 version 条件，是乐观锁：只有当前版本仍匹配，才能成功抢到这条 outbox。
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
        // 只有当前 claim 持有者看到的 version 仍然有效，才能把记录置为 SENT。
        return outboxRepository.markSent(outbox.getId(), outbox.getVersion());
    }

    @Transactional
    public void markPublishFailure(MqOutbox outbox, String errorMessage, LocalDateTime now) {
        int newRetryCount = outbox.getRetryCount() + 1;
        boolean updated;
        if (newRetryCount >= properties.getOutbox().getMaxPublishAttempts()) {
            // outbox retry 耗尽只是说明“发布到 RabbitMQ 失败太多次”，还没有进入 consumer/DLQ。
            updated = outboxRepository.markPermanentlyFailed(
                    outbox.getId(),
                    errorMessage,
                    newRetryCount,
                    outbox.getVersion()
            );
        } else {
            // 还可以重试时，把状态改回 PENDING，并设置 nextRetryAt，等待下一次扫描。
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
        // 这个分支极少见：RabbitMQ 已确认收到，但数据库 markSent 失败。
        // 为避免阻塞扫描线程，交给单线程后台任务去做最终标记。
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
        // DISCARDED 的语义是：不要再自动发布这行，因为它可能已经成功发出过。
        String lastError = "Discarded after markSent conflict; observed status=" + current.getStatus();
        boolean discarded = outboxRepository.markDiscarded(current.getId(), lastError, current.getVersion());
        if (!discarded) {
            throw new IllegalStateException("Failed to discard conflicted outbox row: " + current.getId());
        }
    }
}
