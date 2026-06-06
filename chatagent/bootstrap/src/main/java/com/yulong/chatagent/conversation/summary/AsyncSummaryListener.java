package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import com.yulong.chatagent.conversation.event.LongTermMemoryPromotionRequestedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台异步摘要监听器。
 * <p>
 * 每当一个完整 turn 结束后，它都会尝试判断：
 * <ul>
 *     <li>是否有稳定的（已滑出 L1 tail 的）turn 需要压缩到 L2；</li>
 *     <li>当前 session 是否已有别的摘要任务在跑；</li>
 *     <li>当前事件对应的 anchor 是否已经被历史摘要覆盖；</li>
 *     <li>V2 token-aware policy 是否允许执行压缩。</li>
 * </ul>
 * 只有这些条件都满足时，才真正调用 IncrementalSummarizer。
 * L2 完成后，如果产生了实际待压缩的 turns，同时发出 L3 promotion 事件。
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
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final int l1WindowTurns;
    private final boolean l3Enabled;

    @Autowired
    public AsyncSummaryListener(CompactionBoundaryResolver compactionBoundaryResolver,
                                MemoryCompactionPolicy memoryCompactionPolicy,
                                SummaryWatermarkService summaryWatermarkService,
                                IncrementalSummarizer incrementalSummarizer,
                                RedisLockManager redisLockManager,
                                ApplicationEventPublisher eventPublisher,
                                ObjectProvider<MeterRegistry> meterRegistryProvider,
                                @Value("${chatagent.memory.l1-window-turns:8}") int l1WindowTurns,
                                @Value("${chatagent.memory.l3.enabled:true}") boolean l3Enabled) {
        this.compactionBoundaryResolver = compactionBoundaryResolver;
        this.memoryCompactionPolicy = memoryCompactionPolicy;
        this.summaryWatermarkService = summaryWatermarkService;
        this.incrementalSummarizer = incrementalSummarizer;
        this.redisLockManager = redisLockManager;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.l1WindowTurns = Math.max(l1WindowTurns, 1);
        this.l3Enabled = l3Enabled;
    }

    @Async("summaryExecutor")
    @EventListener
    public void handle(ConversationTurnCompletedEvent event) {
        CompactionBoundary boundary = compactionBoundaryResolver.resolve(event.sessionId(), l1WindowTurns);

        int pendingStableTokens = TokenEstimator.estimateTurns(boundary.pendingStableTurns());
        int l1RawTokenEstimate = TokenEstimator.estimateTurns(boundary.tailTurns());

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
            SummaryResult result = incrementalSummarizer.summarizeWithDetails(
                    event.sessionId(), boundary.stableAnchorSeqNo());

            if (l3Enabled && result.updated() && result.turns() != null && !result.turns().isEmpty()) {
                publishL3Events(event.sessionId(), result);
            }
        } finally {
            redisLockManager.unlock(event.sessionId(), lockToken);
        }
    }

    /**
     * Publishes L3 promotion events. When the result contains segments (from split-and-retry),
     * publishes one event per segment with matching range and turns.
     * Falls back to a single merged event when no segments are present.
     */
    private void publishL3Events(String sessionId, SummaryResult result) {
        if (result.segments() != null && !result.segments().isEmpty()) {
            for (ChatSessionSummarySegmentDTO segment : result.segments()) {
                SummaryWatermarkRange segmentRange = new SummaryWatermarkRange(
                        sessionId, segment.getSeqStartNo() - 1, segment.getSeqEndNo());
                List<AtomicConversationTurn> segmentTurns = filterTurnsByRange(
                        result.turns(), segment.getSeqStartNo(), segment.getSeqEndNo());
                if (!segmentTurns.isEmpty()) {
                    eventPublisher.publishEvent(new LongTermMemoryPromotionRequestedEvent(
                            sessionId, segmentRange, segmentTurns));
                }
            }
        } else {
            eventPublisher.publishEvent(new LongTermMemoryPromotionRequestedEvent(
                    sessionId, result.range(), result.turns()));
        }
    }

    private static List<AtomicConversationTurn> filterTurnsByRange(
            List<AtomicConversationTurn> turns, long startSeqNo, long endSeqNo) {
        List<AtomicConversationTurn> filtered = new ArrayList<>();
        for (AtomicConversationTurn turn : turns) {
            if (turn.endSeqNo() >= startSeqNo && turn.endSeqNo() <= endSeqNo) {
                filtered.add(turn);
            }
        }
        return filtered;
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
