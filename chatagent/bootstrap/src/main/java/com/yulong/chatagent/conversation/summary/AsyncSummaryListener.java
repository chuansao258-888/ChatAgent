package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Background listener that incrementally compresses conversation history once a
 * full user turn has completed.
 */
@Component
@Slf4j
public class AsyncSummaryListener {

    private final TurnBasedContextExtractor turnBasedContextExtractor;
    private final SummaryWatermarkService summaryWatermarkService;
    private final IncrementalSummarizer incrementalSummarizer;
    private final RedisLockManager redisLockManager;
    private final int l1WindowTurns;

    public AsyncSummaryListener(TurnBasedContextExtractor turnBasedContextExtractor,
                                SummaryWatermarkService summaryWatermarkService,
                                IncrementalSummarizer incrementalSummarizer,
                                RedisLockManager redisLockManager,
                                @Value("${chatagent.memory.l1-window-turns:8}") int l1WindowTurns) {
        this.turnBasedContextExtractor = turnBasedContextExtractor;
        this.summaryWatermarkService = summaryWatermarkService;
        this.incrementalSummarizer = incrementalSummarizer;
        this.redisLockManager = redisLockManager;
        this.l1WindowTurns = Math.max(l1WindowTurns, 1);
    }

    @Async("summaryExecutor")
    @EventListener
    public void handle(ConversationTurnCompletedEvent event) {
        long totalTurns = turnBasedContextExtractor.countTurns(event.sessionId());
        if (totalTurns <= l1WindowTurns) {
            return;
        }

        String lockToken = redisLockManager.tryLock(event.sessionId());
        if (lockToken == null) {
            log.debug("Skip summary run because session lock is already held: sessionId={}", event.sessionId());
            return;
        }

        try {
            if (summaryWatermarkService.isAnchorCovered(event.sessionId(), event.lastSeqNo())) {
                return;
            }
            incrementalSummarizer.summarize(event.sessionId(), event.lastSeqNo());
        } finally {
            redisLockManager.unlock(event.sessionId(), lockToken);
        }
    }
}
