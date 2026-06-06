package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompactionBoundaryResolverTest {

    @Mock
    private TurnBasedContextExtractor turnBasedContextExtractor;

    @Mock
    private ChatSessionSummaryRepository chatSessionSummaryRepository;

    private CompactionBoundaryResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CompactionBoundaryResolver(turnBasedContextExtractor, chatSessionSummaryRepository);
    }

    @Test
    void shouldReturnNoStableTurnsWhenBelowL1Window() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(null);

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        assertThat(boundary.hasStableTurns()).isFalse();
        assertThat(boundary.stableAnchorSeqNo()).isZero();
        assertThat(boundary.totalTurns()).isEqualTo(3);
        assertThat(boundary.preservedTailTurns()).isEqualTo(3);
    }

    @Test
    void shouldComputeStableAnchorFromTurnBoundary() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L),
                turn("t-4", 10L, 12L),
                turn("t-5", 13L, 15L),
                turn("t-6", 16L, 18L),
                turn("t-7", 19L, 21L),
                turn("t-8", 22L, 24L),
                turn("t-9", 25L, 27L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(null);

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        // 9 total turns, 8 preserved → 1 stable turn, anchor = endSeqNo of turn 1 = 3
        assertThat(boundary.stableAnchorSeqNo()).isEqualTo(3L);
        assertThat(boundary.stableTurnCount()).isEqualTo(1);
        assertThat(boundary.hasStableTurns()).isTrue();
        assertThat(boundary.totalTurns()).isEqualTo(9);
        assertThat(boundary.preservedTailTurns()).isEqualTo(8);
    }

    @Test
    void shouldUseSummarizedUntilSeqNoFromSummary() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(6L)
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 1);

        assertThat(boundary.summarizedUntilSeqNo()).isEqualTo(6L);
    }

    @Test
    void shouldDetectBackoffActive() {
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(List.of());
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(0L)
                .nextRetryAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        assertThat(boundary.backoffActive()).isTrue();
    }

    @Test
    void shouldDetectBackoffExpired() {
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(List.of());
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(0L)
                .nextRetryAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        assertThat(boundary.backoffActive()).isFalse();
    }

    @Test
    void shouldReadConsecutiveFailuresFromSummary() {
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(List.of());
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(0L)
                .consecutiveFailures(3)
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        assertThat(boundary.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void shouldSplitTurnsIntoStableAndTail() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L),
                turn("t-4", 10L, 12L),
                turn("t-5", 13L, 15L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(null);

        CompactionBoundary boundary = resolver.resolve("s-1", 3);

        // 5 total, 3 preserved → 2 stable
        assertThat(boundary.stableTurns()).hasSize(2);
        assertThat(boundary.stableTurns().get(0).turnId()).isEqualTo("t-1");
        assertThat(boundary.stableTurns().get(1).turnId()).isEqualTo("t-2");
        assertThat(boundary.tailTurns()).hasSize(3);
        assertThat(boundary.tailTurns().get(0).turnId()).isEqualTo("t-3");
    }

    @Test
    void shouldReturnEmptyStableWhenAllTurnsInTail() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(null);

        CompactionBoundary boundary = resolver.resolve("s-1", 5);

        assertThat(boundary.stableTurns()).isEmpty();
        assertThat(boundary.tailTurns()).hasSize(2);
    }

    @Test
    void shouldReturnOnlyPendingStableTurnsAfterWatermark() {
        // 20 turns, L1 tail = 8, summary watermark at endSeqNo of turn 11 (33)
        // Turns 1-12 are stable (outside L1 tail), turns 13-20 are tail
        // Only turn 12 (endSeqNo=36) is pending (endSeqNo > 33 && endSeqNo <= 36)
        List<AtomicConversationTurn> turns = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> turn("t-" + i, (i - 1) * 3L + 1, i * 3L))
                .toList();
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(33L) // turn 11 endSeqNo = 33
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        // 12 stable turns total, but only 1 pending
        assertThat(boundary.stableTurnCount()).isEqualTo(12);
        assertThat(boundary.pendingStableTurnCount()).isEqualTo(1);
        assertThat(boundary.pendingStableTurns()).hasSize(1);
        assertThat(boundary.pendingStableTurns().get(0).turnId()).isEqualTo("t-12");
        assertThat(boundary.pendingStableTurns().get(0).endSeqNo()).isEqualTo(36L);
    }

    @Test
    void shouldReturnEmptyPendingWhenWatermarkCoversAllStable() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L),
                turn("t-4", 10L, 12L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(6L) // covers turns 1-2
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 2);

        // 2 stable turns (t-1, t-2), but watermark at 6 covers both (3 ≤ 6, 6 ≤ 6)
        // stableAnchorSeqNo = 6 (endSeqNo of t-2)
        // pending: endSeqNo > 6 && endSeqNo <= 6 → empty
        assertThat(boundary.hasStableTurns()).isFalse();
        assertThat(boundary.pendingStableTurnCount()).isZero();
        assertThat(boundary.pendingStableTurns()).isEmpty();
    }

    private static AtomicConversationTurn turn(String turnId, long startSeqNo, long endSeqNo) {
        return new AtomicConversationTurn(turnId, startSeqNo, endSeqNo, List.of("msg"), "reply");
    }
}
