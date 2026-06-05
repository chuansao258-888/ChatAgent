package com.yulong.chatagent.conversation.summary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void shouldEstimateAsciiAsOneTokenPerChar() {
        assertThat(TokenEstimator.estimateTokens("hello")).isEqualTo(5);
    }

    @Test
    void shouldEstimateCjkAsTwoTokensPerChar() {
        assertThat(TokenEstimator.estimateTokens("你好")).isEqualTo(4);
    }

    @Test
    void shouldEstimateMixedContent() {
        // "hi" = 2, "你好" = 4, total = 6
        assertThat(TokenEstimator.estimateTokens("hi你好")).isEqualTo(6);
    }

    @Test
    void shouldReturnZeroForNull() {
        assertThat(TokenEstimator.estimateTokens(null)).isZero();
    }

    @Test
    void shouldReturnZeroForEmpty() {
        assertThat(TokenEstimator.estimateTokens("")).isZero();
    }

    @Test
    void shouldReturnZeroForBlank() {
        assertThat(TokenEstimator.estimateTokens("   ")).isZero();
    }

    @Test
    void shouldEstimateTurns() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "t-1", 1L, 3L,
                List.of("hello", "你好world"),
                "response"
        );
        // "hello"=5, "你好world"=2*2+5=9, "response"=8 → total=22
        assertThat(TokenEstimator.estimateTurns(List.of(turn))).isEqualTo(22);
    }

    @Test
    void shouldReturnZeroForEmptyTurnList() {
        assertThat(TokenEstimator.estimateTurns(List.of())).isZero();
    }

    @Test
    void shouldReturnZeroForNullTurnList() {
        assertThat(TokenEstimator.estimateTurns(null)).isZero();
    }

    @Test
    void shouldHandleTurnWithNullUserMessages() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "t-1", 1L, 2L, null, "ok"
        );
        assertThat(TokenEstimator.estimateTurns(List.of(turn))).isEqualTo(2);
    }
}
