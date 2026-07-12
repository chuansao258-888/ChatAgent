package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;


/**
 * 后台异步摘要监听器。
 * <p>
 * 每当一个完整 turn 结束后，它都会尝试判断：
 * <ul>
 *     <li>是否有足够多的未压缩 raw turn 需要滚动压缩到 L2；</li>
 *     <li>当前 session 是否已有别的摘要任务在跑；</li>
 *     <li>当前事件对应的 anchor 是否已经被历史摘要覆盖；</li>
 *     <li>V2 token-aware policy 是否允许执行压缩。</li>
 * </ul>
 * 只有这些条件都满足时，才真正调用 IncrementalSummarizer。
 * L3 promotion job is committed atomically by {@link MemoryCompactionCommitService}.
 */
@Component
@Slf4j
public class AsyncSummaryListener {

    private static final String METRIC_POLICY_DECISIONS = "chatagent.memory.compaction.v2.policy.decisions";

    private final CompactionBoundaryResolver compactionBoundaryResolver;
    private final MemoryCompactionPolicy memoryCompactionPolicy;
    private final SummaryWatermarkService summaryWatermarkService;
    private final IncrementalSummarizer incrementalSummarizer;
    private final RedisLockManager redisLockManager;
    private final MeterRegistry meterRegistry;
    private final int l1WindowTurns;

    @Autowired
    public AsyncSummaryListener(CompactionBoundaryResolver compactionBoundaryResolver,
                                MemoryCompactionPolicy memoryCompactionPolicy,
                                SummaryWatermarkService summaryWatermarkService,
                                IncrementalSummarizer incrementalSummarizer,
                                RedisLockManager redisLockManager,
                                ObjectProvider<MeterRegistry> meterRegistryProvider,
                                @org.springframework.beans.factory.annotation.Value("${chatagent.memory.l1-window-turns:8}") int l1WindowTurns) {
        this.compactionBoundaryResolver = compactionBoundaryResolver;
        this.memoryCompactionPolicy = memoryCompactionPolicy;
        this.summaryWatermarkService = summaryWatermarkService;
        this.incrementalSummarizer = incrementalSummarizer;
        this.redisLockManager = redisLockManager;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.l1WindowTurns = Math.max(l1WindowTurns, 1);
    }

    @Async("summaryExecutor")
    @EventListener
    public void handle(ConversationTurnCompletedEvent event) {
        CompactionBoundary boundary = compactionBoundaryResolver.resolve(event.sessionId(), l1WindowTurns);

        int pendingStableTokens = TokenEstimator.estimateTurns(boundary.pendingStableTurns());
        int l1RawTokenEstimate = TokenEstimator.estimateTurns(boundary.unsummarizedTurns());

        CompactionDecision decision = memoryCompactionPolicy.evaluate(boundary, pendingStableTokens, l1RawTokenEstimate);
        recordDecision(decision);

        if (!decision.shouldCompact()) {
            log.debug("Compaction skipped: sessionId={}, reason={}", event.sessionId(), decision.trigger());
            return;
        }

        String lockToken = redisLockManager.tryLock(event.sessionId());
        if (lockToken == null) {
            log.debug("Skip summary run because session lock is already held: sessionId={}", event.sessionId());
            return;
        }

        try {
            if (summaryWatermarkService.isAnchorCovered(event.sessionId(), boundary.stableAnchorSeqNo())) {
                return;
            }
            incrementalSummarizer.summarizeWithDetails(event.sessionId(), boundary.stableAnchorSeqNo());
        } finally {
            redisLockManager.unlock(event.sessionId(), lockToken);
        }
    }

    private void recordDecision(CompactionDecision decision) {
        if (meterRegistry == null) {
            return;
        }
        try {
            String outcome = decision.shouldCompact() ? "run" : "skip";
            meterRegistry.counter(METRIC_POLICY_DECISIONS,
                    "outcome", outcome,
                    "reason", decision.trigger().name())
                    .increment();
        } catch (Exception e) {
            log.warn("Failed to record compaction policy metric: error={}", e.getMessage());
        }
    }
}
