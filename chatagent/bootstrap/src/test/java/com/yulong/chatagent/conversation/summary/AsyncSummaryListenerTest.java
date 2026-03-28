package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncSummaryListenerTest {

    @Mock
    private TurnBasedContextExtractor turnBasedContextExtractor;

    @Mock
    private SummaryWatermarkService summaryWatermarkService;

    @Mock
    private IncrementalSummarizer incrementalSummarizer;

    @Mock
    private RedisLockManager redisLockManager;

    private AsyncSummaryListener asyncSummaryListener;

    @BeforeEach
    void setUp() {
        asyncSummaryListener = new AsyncSummaryListener(
                turnBasedContextExtractor,
                summaryWatermarkService,
                incrementalSummarizer,
                redisLockManager,
                3
        );
    }

    @Test
    void shouldSkipShortConversationsBeforeLocking() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(3L);

        asyncSummaryListener.handle(event);

        verify(redisLockManager, never()).tryLock("session-1");
        verify(incrementalSummarizer, never()).summarize("session-1", 12L);
    }

    @Test
    void shouldUnlockAndSkipWhenAnchorAlreadyCovered() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(true);

        asyncSummaryListener.handle(event);

        verify(incrementalSummarizer, never()).summarize("session-1", 12L);
        verify(redisLockManager).unlock("session-1", "token-1");
    }

    @Test
    void shouldSummarizeEligibleTurnAndReleaseLock() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(false);

        asyncSummaryListener.handle(event);

        verify(incrementalSummarizer).summarize("session-1", 12L);
        verify(redisLockManager).unlock("session-1", "token-1");
    }
}
