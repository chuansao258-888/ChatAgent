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
        resolver = new CompactionBoundaryResolver(turnBasedContextExtractor, chatSessionSummaryRepository, 2);
    }

    @Test
    void shouldReturnNoStableTurnsWhenWatermarkCoversAllTurns() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(9L)
                .build());

        CompactionBoundary boundary = resolver.resolve("s-1", 8);

        assertThat(boundary.hasStableTurns()).isFalse();
        assertThat(boundary.stableAnchorSeqNo()).isZero();
        assertThat(boundary.totalTurns()).isEqualTo(3);
        assertThat(boundary.preservedTailTurns()).isZero();
        assertThat(boundary.unsummarizedTurnCount()).isZero();
    }

    @Test
    void shouldComputeStableAnchorFromOldestRollingBatch() {
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

        // Batch size is 2, so the next L2 anchor is the endSeqNo of turn 2.
        assertThat(boundary.stableAnchorSeqNo()).isEqualTo(6L);
        assertThat(boundary.stableTurnCount()).isEqualTo(2);
        assertThat(boundary.unsummarizedTurnCount()).isEqualTo(9);
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

        // Stable now means the next rolling batch; tail means all unsummarized raw turns.
        assertThat(boundary.stableTurns()).hasSize(2);
        assertThat(boundary.stableTurns().get(0).turnId()).isEqualTo("t-1");
        assertThat(boundary.stableTurns().get(1).turnId()).isEqualTo("t-2");
        assertThat(boundary.tailTurns()).hasSize(5);
        assertThat(boundary.tailTurns().get(0).turnId()).isEqualTo("t-1");
    }

    @Test
    void shouldUseAllPendingTurnsWhenPendingCountIsBelowBatchSize() {
        resolver = new CompactionBoundaryResolver(turnBasedContextExtractor, chatSessionSummaryRepository, 5);
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(null);

        CompactionBoundary boundary = resolver.resolve("s-1", 5);

        assertThat(boundary.stableTurns()).hasSize(2);
        assertThat(boundary.stableAnchorSeqNo()).isEqualTo(6L);
        assertThat(boundary.tailTurns()).hasSize(2);
    }

    @Test
    void shouldReturnOnlyOldestBatchAfterWatermark() {
        // 20 turns, summary watermark at endSeqNo of turn 11 (33).
        // The next rolling batch is turns 12-13 because resolver batch size is 2.
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

        assertThat(boundary.unsummarizedTurnCount()).isEqualTo(9);
        assertThat(boundary.pendingStableTurnCount()).isEqualTo(2);
        assertThat(boundary.pendingStableTurns()).hasSize(2);
        assertThat(boundary.pendingStableTurns().get(0).turnId()).isEqualTo("t-12");
        assertThat(boundary.pendingStableTurns().get(1).turnId()).isEqualTo("t-13");
        assertThat(boundary.stableAnchorSeqNo()).isEqualTo(39L);
    }

    @Test
    void shouldReturnEmptyPendingWhenWatermarkCoversAllTurns() {
        List<AtomicConversationTurn> turns = List.of(
                turn("t-1", 1L, 3L),
                turn("t-2", 4L, 6L),
                turn("t-3", 7L, 9L),
                turn("t-4", 10L, 12L)
        );
        when(turnBasedContextExtractor.extractAllTurns("s-1")).thenReturn(turns);
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("s-1")
                .summarizedUntilSeqNo(12L)
                .build();
        when(chatSessionSummaryRepository.findBySessionId("s-1")).thenReturn(summary);

        CompactionBoundary boundary = resolver.resolve("s-1", 2);

        assertThat(boundary.hasStableTurns()).isFalse();
        assertThat(boundary.pendingStableTurnCount()).isZero();
        assertThat(boundary.pendingStableTurns()).isEmpty();
    }

    private static AtomicConversationTurn turn(String turnId, long startSeqNo, long endSeqNo) {
        return new AtomicConversationTurn(turnId, startSeqNo, endSeqNo, List.of("msg"), "reply");
    }
}
