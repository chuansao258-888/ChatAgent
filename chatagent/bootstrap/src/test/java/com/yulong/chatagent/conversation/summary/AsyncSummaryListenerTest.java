package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import com.yulong.chatagent.conversation.event.LongTermMemoryPromotionRequestedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncSummaryListenerTest {

    @Mock
    private CompactionBoundaryResolver compactionBoundaryResolver;

    @Mock
    private MemoryCompactionPolicy memoryCompactionPolicy;

    @Mock
    private SummaryWatermarkService summaryWatermarkService;

    @Mock
    private IncrementalSummarizer incrementalSummarizer;

    @Mock
    private RedisLockManager redisLockManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private SimpleMeterRegistry simpleMeterRegistry;

    private AsyncSummaryListener asyncSummaryListener;

    @BeforeEach
    void setUp() {
        simpleMeterRegistry = new SimpleMeterRegistry();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(simpleMeterRegistry);
        asyncSummaryListener = new AsyncSummaryListener(
                compactionBoundaryResolver,
                memoryCompactionPolicy,
                summaryWatermarkService,
                incrementalSummarizer,
                redisLockManager,
                eventPublisher,
                meterRegistryProvider,
                3,
                true
        );
    }

    @Test
    void shouldDelegateToPolicyWhenNoStableTurns() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        CompactionBoundary boundary = noStableBoundary("session-1");
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(false, CompactionTrigger.NO_STABLE_TURNS));

        asyncSummaryListener.handle(event);

        verify(memoryCompactionPolicy).evaluate(boundary, 0, 0);
        verify(redisLockManager, never()).tryLock("session-1");
        assertThat(policyCounter("skip", "NO_STABLE_TURNS")).isEqualTo(1.0);
    }

    @Test
    void shouldSkipWhenPolicyDecidesNotToCompact() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(false, CompactionTrigger.BELOW_THRESHOLD));

        asyncSummaryListener.handle(event);

        verify(redisLockManager, never()).tryLock("session-1");
        assertThat(policyCounter("skip", "BELOW_THRESHOLD")).isEqualTo(1.0);
    }

    @Test
    void shouldUnlockAndSkipWhenAnchorAlreadyCovered() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.PENDING_TURNS));
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 8L)).thenReturn(true);

        asyncSummaryListener.handle(event);

        verify(incrementalSummarizer, never()).summarizeWithDetails(anyString(), anyLong());
        verify(redisLockManager).unlock("session-1", "token-1");
    }

    @Test
    void shouldSummarizeWithStableAnchorNotEventAnchor() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.PENDING_TURNS));
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 8L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 8L))
                .thenReturn(new SummaryResult(true,
                        new SummaryWatermarkRange("session-1", 4L, 8L),
                        List.of(new AtomicConversationTurn("t-1", 5L, 8L, List.of("hello"), "hi"))));

        asyncSummaryListener.handle(event);

        // Must use stable anchor (8L), NOT event.lastSeqNo (12L)
        verify(incrementalSummarizer).summarizeWithDetails("session-1", 8L);
        verify(redisLockManager).unlock("session-1", "token-1");
        assertThat(policyCounter("run", "PENDING_TURNS")).isEqualTo(1.0);
    }

    @Test
    void shouldPublishL3EventWhenL2HasPendingTurns() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        AtomicConversationTurn turn = new AtomicConversationTurn("t-1", 5L, 8L, List.of("hello"), "hi");
        SummaryWatermarkRange range = new SummaryWatermarkRange("session-1", 4L, 8L);
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.PENDING_TURNS));
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 8L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 8L))
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
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.PENDING_TURNS));
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 8L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 8L))
                .thenReturn(new SummaryResult(false,
                        new SummaryWatermarkRange("session-1", 8L, 8L),
                        List.of()));

        asyncSummaryListener.handle(event);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishL3EventWhenTurnsAreEmpty() {
        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.PENDING_TURNS));
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 8L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 8L))
                .thenReturn(new SummaryResult(true,
                        new SummaryWatermarkRange("session-1", 4L, 8L),
                        List.of()));

        asyncSummaryListener.handle(event);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldNotPublishL3EventWhenL3IsDisabled() {
        AsyncSummaryListener listenerWithL3Disabled = new AsyncSummaryListener(
                compactionBoundaryResolver,
                memoryCompactionPolicy,
                summaryWatermarkService,
                incrementalSummarizer,
                redisLockManager,
                eventPublisher,
                meterRegistryProvider,
                3,
                false
        );

        ConversationTurnCompletedEvent event = new ConversationTurnCompletedEvent("session-1", "turn-1", 12L);
        AtomicConversationTurn turn = new AtomicConversationTurn("t-1", 5L, 8L, List.of("hello"), "hi");
        CompactionBoundary boundary = stableBoundary("session-1", 8L);
        when(compactionBoundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(memoryCompactionPolicy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.PENDING_TURNS));
        when(redisLockManager.tryLock("session-1")).thenReturn("token-1");
        when(summaryWatermarkService.isAnchorCovered("session-1", 8L)).thenReturn(false);
        when(incrementalSummarizer.summarizeWithDetails("session-1", 8L))
                .thenReturn(new SummaryResult(true,
                        new SummaryWatermarkRange("session-1", 4L, 8L),
                        List.of(turn)));

        listenerWithL3Disabled.handle(event);

        verify(eventPublisher, never()).publishEvent(any());
    }

    private double policyCounter(String outcome, String reason) {
        return simpleMeterRegistry
                .counter("chatagent.memory.compaction.v2.policy.decisions",
                        "outcome", outcome, "reason", reason)
                .count();
    }

    private static CompactionBoundary noStableBoundary(String sessionId) {
        return new CompactionBoundary(sessionId, 0L, 0L, 3, 3, List.of(), 0, false);
    }

    private static CompactionBoundary stableBoundary(String sessionId, long stableAnchorSeqNo) {
        return new CompactionBoundary(sessionId, 0L, stableAnchorSeqNo, 5, 3, List.of(), 0, false);
    }
}
