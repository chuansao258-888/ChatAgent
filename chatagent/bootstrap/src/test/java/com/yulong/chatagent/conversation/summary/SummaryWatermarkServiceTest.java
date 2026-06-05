package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryWatermarkServiceTest {

    @Mock
    private ChatSessionSummaryRepository chatSessionSummaryRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private SummaryWatermarkService summaryWatermarkService;

    @BeforeEach
    void setUp() {
        summaryWatermarkService = new SummaryWatermarkService(chatSessionSummaryRepository, chatMessageRepository);
    }

    @Test
    void shouldResolvePendingRangeAgainstLatestSeqNo() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(12L)
                        .build()
        );
        when(chatMessageRepository.findMaxSeqNoBySessionId("session-1")).thenReturn(20L);

        SummaryWatermarkRange range = summaryWatermarkService.resolvePendingRange("session-1");

        assertThat(range.lastSummarizedSeqNo()).isEqualTo(12L);
        assertThat(range.anchorSeqNo()).isEqualTo(20L);
        assertThat(range.hasPendingMessages()).isTrue();
    }

    @Test
    void shouldDefaultLastSummarizedSeqNoToZeroWhenSummaryMissing() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatMessageRepository.findMaxSeqNoBySessionId("session-1")).thenReturn(5L);

        SummaryWatermarkRange range = summaryWatermarkService.resolvePendingRange("session-1");

        assertThat(range.lastSummarizedSeqNo()).isZero();
        assertThat(range.anchorSeqNo()).isEqualTo(5L);
        assertThat(range.hasPendingMessages()).isTrue();
    }

    @Test
    void shouldResolveEmptyRangeWhenSessionHasNoMessages() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatMessageRepository.findMaxSeqNoBySessionId("session-1")).thenReturn(null);

        SummaryWatermarkRange range = summaryWatermarkService.resolvePendingRange("session-1");

        assertThat(range.lastSummarizedSeqNo()).isZero();
        assertThat(range.anchorSeqNo()).isZero();
        assertThat(range.hasPendingMessages()).isFalse();
    }

    @Test
    void shouldTreatCoveredAnchorAsNoPendingMessages() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(20L)
                        .build()
        );

        SummaryWatermarkRange range = summaryWatermarkService.resolvePendingRange("session-1", 18L);

        assertThat(range.hasPendingMessages()).isFalse();
        assertThat(summaryWatermarkService.isAnchorCovered("session-1", 18L)).isTrue();
    }

    @Test
    void shouldReturnEmptyMessagesWhenNoPendingRangeExists() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(9L)
                        .build()
        );

        List<ChatMessageDTO> result = summaryWatermarkService.loadPendingMessages("session-1", 9L);

        assertThat(result).isEmpty();
        verify(chatMessageRepository, never()).findBySessionIdAndSeqRange("session-1", 9L, 9L);
    }

    @Test
    void shouldLoadPendingMessagesWithinSeqRange() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(5L)
                        .build()
        );
        List<ChatMessageDTO> pendingMessages = List.of(
                ChatMessageDTO.builder().id("m-6").seqNo(6L).build(),
                ChatMessageDTO.builder().id("m-7").seqNo(7L).build()
        );
        when(chatMessageRepository.findBySessionIdAndSeqRange("session-1", 5L, 7L)).thenReturn(pendingMessages);

        List<ChatMessageDTO> result = summaryWatermarkService.loadPendingMessages("session-1", 7L);

        assertThat(result).containsExactlyElementsOf(pendingMessages);
        verify(chatMessageRepository).findBySessionIdAndSeqRange("session-1", 5L, 7L);
    }
}
