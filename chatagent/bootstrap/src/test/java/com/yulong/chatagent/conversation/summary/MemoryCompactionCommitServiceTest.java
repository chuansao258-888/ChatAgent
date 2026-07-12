package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.memory.port.MemoryPromotionJobRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryCompactionCommitServiceTest {
    @Mock private ChatSessionSummaryRepository summaryRepository;
    @Mock private ChatSessionSummarySegmentRepository segmentRepository;
    @Mock private MemoryPromotionJobRepository jobRepository;

    private MemoryCompactionCommitService service;
    private ChatSessionSummarySegmentDTO segment;
    private ChatSessionSummaryDTO summary;

    @BeforeEach
    void setUp() {
        service = new MemoryCompactionCommitService(summaryRepository, segmentRepository, jobRepository, true);
        segment = ChatSessionSummarySegmentDTO.builder()
                .sessionId("session-1").seqStartNo(5L).seqEndNo(8L).build();
        summary = ChatSessionSummaryDTO.builder()
                .sessionId("session-1").summarizedUntilSeqNo(8L).build();
    }

    @Test
    void shouldDeclareTransactionalBoundary() throws Exception {
        assertThat(MemoryCompactionCommitService.class
                .getMethod("commit", long.class, ChatSessionSummarySegmentDTO.class, ChatSessionSummaryDTO.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void shouldCommitSegmentSummaryAndJobInOrder() {
        when(summaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder().summarizedUntilSeqNo(4L).build());
        when(summaryRepository.saveOrUpdate(summary)).thenReturn(true);

        assertThat(service.commit(4L, segment, summary))
                .isEqualTo(MemoryCompactionCommitService.CommitOutcome.COMMITTED);

        InOrder order = inOrder(segmentRepository, summaryRepository, jobRepository);
        order.verify(segmentRepository).insert(segment);
        order.verify(summaryRepository).saveOrUpdate(summary);
        order.verify(jobRepository).insertPendingForSession("session-1", 5L, 8L);
    }

    @Test
    void shouldRejectStaleGeneratedResultWithoutWriting() {
        when(summaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder().summarizedUntilSeqNo(8L).build());

        assertThat(service.commit(4L, segment, summary))
                .isEqualTo(MemoryCompactionCommitService.CommitOutcome.STALE_WATERMARK);

        verify(segmentRepository, never()).insert(segment);
        verify(jobRepository, never()).insertPendingForSession("session-1", 5L, 8L);
    }

    @Test
    void shouldFailTransactionWhenSummaryCannotAdvance() {
        when(summaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder().summarizedUntilSeqNo(4L).build());
        when(summaryRepository.saveOrUpdate(summary)).thenReturn(false);

        assertThatThrownBy(() -> service.commit(4L, segment, summary))
                .isInstanceOf(IllegalStateException.class);

        verify(jobRepository, never()).insertPendingForSession("session-1", 5L, 8L);
    }
}
