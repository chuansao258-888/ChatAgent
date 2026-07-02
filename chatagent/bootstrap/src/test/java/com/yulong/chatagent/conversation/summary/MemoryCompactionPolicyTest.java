package com.yulong.chatagent.conversation.summary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCompactionPolicyTest {

    private static CompactionBoundary boundaryWith(int stableCount, int totalTurns, int preservedTail) {
        List<AtomicConversationTurn> turns = java.util.stream.IntStream.range(0, totalTurns)
                .mapToObj(i -> new AtomicConversationTurn("t-" + (i + 1), i * 3L + 1, i * 3L + 3, List.of("msg"), "reply"))
                .toList();
        return new CompactionBoundary(
                "s-1", 0L,
                stableCount > 0 ? turns.get(stableCount - 1).endSeqNo() : 0L,
                totalTurns, preservedTail, turns,
                0, false);
    }

    private static CompactionBoundary backoffBoundary() {
        return new CompactionBoundary("s-1", 0L, 10L, 10, 8,
                List.of(), 3, true);
    }

    @Test
    void shouldReturnDisabledWhenV2NotEnabled() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(false, 1, 1200, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        CompactionDecision decision = policy.evaluate(boundary, 5000, 3000);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.DISABLED);
    }

    @Test
    void shouldReturnNoStableTurnsWhenNoStableTurnsAvailable() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 1, 1200, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(0, 5, 5);

        CompactionDecision decision = policy.evaluate(boundary, 0, 3000);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.NO_STABLE_TURNS);
    }

    @Test
    void shouldReturnBackoffActiveWhenBackoffIsSet() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 1, 1200, 0.75, 4000);

        CompactionDecision decision = policy.evaluate(backoffBoundary(), 5000, 3000);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.BACKOFF_ACTIVE);
    }

    @Test
    void shouldTriggerOnPendingTurns() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 10, 1200, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        CompactionDecision decision = policy.evaluate(boundary, 100, 2000);

        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.UNSUMMARIZED_TURNS);
    }

    @Test
    void shouldTriggerOnPendingTokensWhenTurnCountBelowThreshold() {
        // trigger-unsummarized-turns=30 is above total unsummarized count of 10.
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 30, 1200, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        CompactionDecision decision = policy.evaluate(boundary, 1500, 2000);

        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.PENDING_TOKENS);
    }

    @Test
    void shouldNotTriggerOnPendingTokensWhenBelowTokenThreshold() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 30, 5000, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        CompactionDecision decision = policy.evaluate(boundary, 1500, 2000);

        // tokens=1500 < minPendingTokens=5000, L1=2000 < 4000*0.75=3000
        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.BELOW_THRESHOLD);
    }

    @Test
    void shouldTriggerOnL1PressureWhenTurnAndTokenThresholdsNotMet() {
        // Both turn and token thresholds set above fixture values
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 30, 5000, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        // L1 raw estimate = 3500 > 4000 * 0.75 = 3000
        CompactionDecision decision = policy.evaluate(boundary, 500, 3500);

        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.L1_TOKEN_PRESSURE);
    }

    @Test
    void shouldReturnBelowThresholdWhenNothingTriggers() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 30, 5000, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        CompactionDecision decision = policy.evaluate(boundary, 500, 2000);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.BELOW_THRESHOLD);
    }

    @Test
    void shouldPrioritizePendingTurnsOverTokensAndPressure() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 1, 100, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(3, 10, 7);

        CompactionDecision decision = policy.evaluate(boundary, 5000, 3500);

        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.UNSUMMARIZED_TURNS);
    }

    @Test
    void shouldPrioritizePendingTokensOverL1Pressure() {
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 30, 100, 0.75, 4000);
        CompactionBoundary boundary = boundaryWith(2, 10, 8);

        CompactionDecision decision = policy.evaluate(boundary, 5000, 3500);

        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.PENDING_TOKENS);
    }

    @Test
    void shouldNotTriggerOnAlreadySummarizedStableTurns() {
        // 20 turns, L1 tail = 8, so 12 stable. Watermark at turn 11 (endSeqNo=33).
        // Only one batch turn is selected, and total unsummarized turns=9 < trigger=20.
        // With pending token estimate too low, should return BELOW_THRESHOLD.
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 20, 5000, 0.75, 4000);

        List<AtomicConversationTurn> turns = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new AtomicConversationTurn(
                        "t-" + (i + 1), i * 3L + 1, i * 3L + 3, List.of("msg"), "reply"))
                .toList();
        // stableAnchorSeqNo = endSeqNo of turn 12 = 36
        // summarizedUntilSeqNo = 33 (covers turns 1-11)
        // unsummarizedTurnCount = 9 (turns 12-20), still below trigger=20.
        CompactionBoundary boundary = new CompactionBoundary(
                "s-1", 33L, 36L, 20, 8, turns, 0, false);

        // Tokens and L1 pressure below threshold
        CompactionDecision decision = policy.evaluate(boundary, 500, 2000);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.BELOW_THRESHOLD);
    }

    @Test
    void shouldTriggerOnPendingTokensWithExistingWatermark() {
        // Same setup but tokens exceed threshold
        MemoryCompactionPolicy policy = new MemoryCompactionPolicy(true, 20, 100, 0.75, 4000);

        List<AtomicConversationTurn> turns = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new AtomicConversationTurn(
                        "t-" + (i + 1), i * 3L + 1, i * 3L + 3, List.of("msg"), "reply"))
                .toList();
        CompactionBoundary boundary = new CompactionBoundary(
                "s-1", 33L, 36L, 20, 8, turns, 0, false);

        CompactionDecision decision = policy.evaluate(boundary, 500, 2000);

        // unsummarized turn count is below trigger=20, but tokens from the
        // selected batch estimate (passed as 500) >= minPendingTokens=100.
        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.trigger()).isEqualTo(CompactionTrigger.PENDING_TOKENS);
    }
}
