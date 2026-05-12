package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 后台异步摘要监听器。
 * <p>
 * 每当一个完整 turn 结束后，它都会尝试判断：
 * <ul>
 *     <li>这次会话是否已经大到需要把旧内容压进 L2 摘要；</li>
 *     <li>当前 session 是否已有别的摘要任务在跑；</li>
 *     <li>当前事件对应的 anchor 是否已经被历史摘要覆盖。</li>
 * </ul>
 * 只有这些条件都满足时，才真正调用 IncrementalSummarizer。
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
            // 最近几轮仍然保留在 L1 原始记忆中，没必要过早压缩。
            return;
        }

        String lockToken = redisLockManager.tryLock(event.sessionId());
        if (lockToken == null) {
            // 同一 session 已有摘要任务在跑时，当前这次事件直接跳过即可，
            // 后续任务会以更大的 anchor 一并覆盖掉这部分内容。
            log.debug("Skip summary run because session lock is already held: sessionId={}", event.sessionId());
            return;
        }

        try {
            if (summaryWatermarkService.isAnchorCovered(event.sessionId(), event.lastSeqNo())) {
                // 当前 anchor 已经被更早或更晚的摘要任务覆盖，不需要重复跑。
                return;
            }
            incrementalSummarizer.summarize(event.sessionId(), event.lastSeqNo());
        } finally {
            // 无论 summarize 成功还是失败，都必须释放 session 级摘要锁。
            redisLockManager.unlock(event.sessionId(), lockToken);
        }
    }
}
