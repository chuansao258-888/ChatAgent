package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.event.ConversationTurnCompletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncSummaryListenerTest {
    @Mock private CompactionBoundaryResolver boundaryResolver;
    @Mock private MemoryCompactionPolicy policy;
    @Mock private SummaryWatermarkService watermarkService;
    @Mock private IncrementalSummarizer summarizer;
    @Mock private RedisLockManager lockManager;
    @Mock private ObjectProvider<MeterRegistry> meterProvider;

    private SimpleMeterRegistry registry;
    private AsyncSummaryListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(meterProvider.getIfAvailable()).thenReturn(registry);
        listener = new AsyncSummaryListener(
                boundaryResolver, policy, watermarkService, summarizer, lockManager, meterProvider, 3);
    }

    @Test
    void shouldSkipWithoutStableTurns() {
        CompactionBoundary boundary = new CompactionBoundary(
                "session-1", 0L, 0L, 3, 3, List.of(), 0, false);
        when(boundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(policy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(false, CompactionTrigger.NO_STABLE_TURNS));

        listener.handle(new ConversationTurnCompletedEvent("session-1", "turn-1", 12L));

        verify(lockManager, never()).tryLock("session-1");
        assertThat(registry.counter("chatagent.memory.compaction.v2.policy.decisions",
                "outcome", "skip", "reason", "NO_STABLE_TURNS").count()).isEqualTo(1.0);
    }

    @Test
    void shouldSummarizeUsingStableAnchorAndAlwaysUnlock() {
        CompactionBoundary boundary = new CompactionBoundary(
                "session-1", 0L, 8L, 5, 3, List.of(), 0, false);
        when(boundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(policy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.UNSUMMARIZED_TURNS));
        when(lockManager.tryLock("session-1")).thenReturn("lock");
        when(watermarkService.isAnchorCovered("session-1", 8L)).thenReturn(false);

        listener.handle(new ConversationTurnCompletedEvent("session-1", "turn-1", 12L));

        verify(summarizer).summarizeWithDetails("session-1", 8L);
        verify(lockManager).unlock("session-1", "lock");
    }

    @Test
    void shouldSkipCoveredAnchorAndUnlock() {
        CompactionBoundary boundary = new CompactionBoundary(
                "session-1", 0L, 8L, 5, 3, List.of(), 0, false);
        when(boundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(policy.evaluate(boundary, 0, 0))
                .thenReturn(new CompactionDecision(true, CompactionTrigger.UNSUMMARIZED_TURNS));
        when(lockManager.tryLock("session-1")).thenReturn("lock");
        when(watermarkService.isAnchorCovered("session-1", 8L)).thenReturn(true);

        listener.handle(new ConversationTurnCompletedEvent("session-1", "turn-1", 12L));

        verify(summarizer, never()).summarizeWithDetails("session-1", 8L);
        verify(lockManager).unlock("session-1", "lock");
    }

    @Test
    void shouldPassStableAndRawTokenEstimatesToPolicy() {
        AtomicConversationTurn stable = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("alpha"), "beta");
        AtomicConversationTurn tail = new AtomicConversationTurn(
                "turn-2", 5L, 8L, List.of("gamma"), "delta");
        CompactionBoundary boundary = new CompactionBoundary(
                "session-1", 0L, 4L, 2, 2, List.of(stable, tail), 0, false);
        int stableTokens = TokenEstimator.estimateTurns(List.of(stable));
        int rawTokens = TokenEstimator.estimateTurns(List.of(stable, tail));
        when(boundaryResolver.resolve("session-1", 3)).thenReturn(boundary);
        when(policy.evaluate(boundary, stableTokens, rawTokens))
                .thenReturn(new CompactionDecision(false, CompactionTrigger.BELOW_THRESHOLD));

        listener.handle(new ConversationTurnCompletedEvent("session-1", "turn-1", 8L));

        verify(policy).evaluate(boundary, stableTokens, rawTokens);
    }
}
