package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import com.yulong.chatagent.conversation.event.LongTermMemoryPromotionRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AsyncSummaryListener asyncSummaryListener;

    @BeforeEach
    void setUp() {
        asyncSummaryListener = new AsyncSummaryListener(
                turnBasedContextExtractor,
                summaryWatermarkService,
                incrementalSummarizer,
                redisLockManager,
                eventPublisher,
                3,
                true
        );
    }

    @Test
    void shouldSkipShortConversationsBeforeLocking() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(3L);

        asyncSummaryListener.handle(event);

        verify(redisLockManager, never()).tryLock("session-1");
        verify(incrementalSummarizer, never()).summarizeWithDetails("session-1", 12L);
    }

    @Test
    void shouldUnlockAndSkipWhenAnchorAlreadyCovered() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(true);

        asyncSummaryListener.handle(event);

        verify(incrementalSummarizer, never()).summarizeWithDetails("session-1", 12L);
        verify(redisLockManager).unlock("session-1", "token-1");
    }

    @Test
    void shouldSummarizeEligibleTurnAndReleaseLock() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 12L))
                .thenReturn(new SummaryResult(true,
                        new SummaryWatermarkRange("session-1", 4L, 12L),
                        List.of(new AtomicConversationTurn("t-1", 5L, 8L, List.of("hello"), "hi"))));

        asyncSummaryListener.handle(event);

        verify(incrementalSummarizer).summarizeWithDetails("session-1", 12L);
        verify(redisLockManager).unlock("session-1", "token-1");
    }

    @Test
    void shouldPublishL3EventWhenL2HasPendingTurns() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        AtomicConversationTurn turn = new AtomicConversationTurn("t-1", 5L, 8L, List.of("hello"), "hi");
        SummaryWatermarkRange range = new SummaryWatermarkRange("session-1", 4L, 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 12L))
                .thenReturn(new SummaryResult(true, range, List.of(turn)));

        asyncSummaryListener.handle(event);

        ArgumentCaptor<LongTermMemoryPromotionRequestedEvent> captor =
                ArgumentCaptor.forClass(LongTermMemoryPromotionRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        LongTermMemoryPromotionRequestedEvent published = captor.getValue();
        assertThat(published.sessionId()).isEqualTo("session-1");
        assertThat(published.range()).isEqualTo(range);
        assertThat(published.turns()).containsExactly(turn);
    }

    @Test
    void shouldNotPublishL3EventWhenSummaryNotUpdated() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 12L))
                .thenReturn(new SummaryResult(false,
                        new SummaryWatermarkRange("session-1", 12L, 12L),
                        List.of()));

        asyncSummaryListener.handle(event);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishL3EventWhenTurnsAreEmpty() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 12L))
                .thenReturn(new SummaryResult(true,
                        new SummaryWatermarkRange("session-1", 4L, 12L),
                        List.of()));

        asyncSummaryListener.handle(event);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishL3EventWhenL3IsDisabled() {
        AsyncSummaryListener listenerWithL3Disabled = new AsyncSummaryListener(
                turnBasedContextExtractor,
                summaryWatermarkService,
                incrementalSummarizer,
                redisLockManager,
                eventPublisher,
                3,
                false
        );

        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        AtomicConversationTurn turn = new AtomicConversationTurn("t-1", 5L, 8L, List.of("hello"), "hi");
        when(turnBasedContextExtractor.countTurns("session-1")).thenReturn(5L);
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 12L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 12L))
                .thenReturn(new SummaryResult(true,
                        new SummaryWatermarkRange("session-1", 4L, 12L),
                        List.of(turn)));

        listenerWithL3Disabled.handle(event);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
