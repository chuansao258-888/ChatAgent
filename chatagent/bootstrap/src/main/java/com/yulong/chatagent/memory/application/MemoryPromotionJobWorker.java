package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.TurnBasedContextExtractor;
import com.yulong.chatagent.memory.port.MemoryPromotionJobRepository;
import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(name = "chatagent.memory.l3.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MemoryPromotionJobWorker {
    private static final int MAX_JOBS_PER_POLL = 8;

    private final MemoryPromotionJobRepository jobRepository;
    private final TurnBasedContextExtractor turnExtractor;
    private final LongTermMemoryPromotionService promotionService;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final int staleSeconds;

    public MemoryPromotionJobWorker(MemoryPromotionJobRepository jobRepository,
                                    TurnBasedContextExtractor turnExtractor,
                                    LongTermMemoryPromotionService promotionService,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider,
                                    @Value("${chatagent.memory.l3.job-max-attempts:5}") int maxAttempts,
                                    @Value("${chatagent.memory.l3.job-stale-seconds:300}") int staleSeconds) {
        this.jobRepository = jobRepository;
        this.turnExtractor = turnExtractor;
        this.promotionService = promotionService;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.staleSeconds = Math.max(staleSeconds, 60);
        if (meterRegistry != null) {
            meterRegistry.gauge("chatagent.memory.promotion.backlog", jobRepository,
                    repository -> repository.countBacklog());
        }
    }

    @Scheduled(fixedDelayString = "${chatagent.memory.l3.job-poll-ms:5000}")
    public void poll() {
        int reclaimed = jobRepository.reclaimStale(LocalDateTime.now().minusSeconds(staleSeconds));
        if (reclaimed > 0) {
            record("reclaimed", reclaimed);
        }
        for (int i = 0; i < MAX_JOBS_PER_POLL; i++) {
            MemoryPromotionJobDTO job = jobRepository.claimNextDue(LocalDateTime.now());
            if (job == null) {
                return;
            }
            process(job);
        }
    }

    private void process(MemoryPromotionJobDTO job) {
        try {
            List<AtomicConversationTurn> turns = turnExtractor.extractTurnsInRange(
                    job.getSessionId(), job.getSeqStartNo() - 1, job.getSeqEndNo());
            if (turns.isEmpty()) {
                recordTransition(jobRepository.markCompleted(job.getId()), "no_source");
                return;
            }
            promotionService.promote(job, turns);
            recordTransition(jobRepository.markCompleted(job.getId()), "completed");
        } catch (Exception e) {
            String error = truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500);
            if (job.getAttempts() != null && job.getAttempts() >= maxAttempts) {
                recordTransition(jobRepository.markFailed(job.getId(), error), "failed");
            } else {
                long delaySeconds = Math.min(300L, 5L << Math.max(0, (job.getAttempts() == null ? 1 : job.getAttempts()) - 1));
                recordTransition(jobRepository.markRetry(
                        job.getId(), LocalDateTime.now().plusSeconds(delaySeconds), error), "retry");
            }
            log.warn("Memory promotion job failed: jobId={}, sessionId={}, attempt={}, errorClass={}",
                    job.getId(), job.getSessionId(), job.getAttempts(), e.getClass().getSimpleName());
        }
    }

    private void recordTransition(boolean changed, String outcome) {
        record(changed ? outcome : "claim_lost", 1);
    }

    private void record(String outcome, int amount) {
        if (meterRegistry != null) {
            meterRegistry.counter("chatagent.memory.promotion.jobs", "outcome", outcome).increment(amount);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
