package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.TurnBasedContextExtractor;
import com.yulong.chatagent.memory.port.MemoryPromotionJobRepository;
import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryPromotionJobWorkerTest {
    @Mock private MemoryPromotionJobRepository jobRepository;
    @Mock private TurnBasedContextExtractor turnExtractor;
    @Mock private LongTermMemoryPromotionService promotionService;
    @Mock private ObjectProvider<MeterRegistry> meterProvider;

    private MemoryPromotionJobWorker worker;
    private MemoryPromotionJobDTO job;

    @BeforeEach
    void setUp() {
        when(meterProvider.getIfAvailable()).thenReturn(null);
        worker = new MemoryPromotionJobWorker(jobRepository, turnExtractor, promotionService,
                meterProvider, 3, 300);
        job = MemoryPromotionJobDTO.builder().id("job-1").userId("user-1")
                .sessionId("session-1").seqStartNo(5L).seqEndNo(8L).attempts(1).build();
    }

    @Test
    void shouldCompleteMissingDeletedSourceWithoutRetry() {
        when(jobRepository.claimNextDue(any())).thenReturn(job, (MemoryPromotionJobDTO) null);
        when(turnExtractor.extractTurnsInRange("session-1", 4L, 8L)).thenReturn(List.of());

        worker.poll();

        verify(jobRepository).markCompleted("job-1");
        verify(jobRepository, never()).markRetry(anyString(), any(LocalDateTime.class), anyString());
    }

    @Test
    void shouldCompleteSuccessfulPromotion() {
        List<AtomicConversationTurn> turns = List.of(
                new AtomicConversationTurn("turn-1", 5L, 8L, List.of("user"), "assistant"));
        when(jobRepository.claimNextDue(any())).thenReturn(job, (MemoryPromotionJobDTO) null);
        when(turnExtractor.extractTurnsInRange("session-1", 4L, 8L)).thenReturn(turns);

        worker.poll();

        verify(promotionService).promote(job, turns);
        verify(jobRepository).markCompleted("job-1");
    }

    @Test
    void shouldRetryBeforeMaxAttempts() {
        when(jobRepository.claimNextDue(any())).thenReturn(job, (MemoryPromotionJobDTO) null);
        when(turnExtractor.extractTurnsInRange("session-1", 4L, 8L))
                .thenThrow(new IllegalStateException("temporary"));

        worker.poll();

        verify(jobRepository).markRetry(anyString(), any(LocalDateTime.class), anyString());
        verify(jobRepository, never()).markFailed(anyString(), anyString());
    }

    @Test
    void shouldStopAfterMaxAttempts() {
        job.setAttempts(3);
        when(jobRepository.claimNextDue(any())).thenReturn(job, (MemoryPromotionJobDTO) null);
        when(turnExtractor.extractTurnsInRange("session-1", 4L, 8L))
                .thenThrow(new IllegalStateException("permanent"));

        worker.poll();

        verify(jobRepository).markFailed(anyString(), anyString());
        verify(jobRepository, never()).markRetry(anyString(), any(LocalDateTime.class), anyString());
    }

    @Test
    void shouldRecordClaimLostInsteadOfFalseCompletion() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(meterProvider.getIfAvailable()).thenReturn(registry);
        worker = new MemoryPromotionJobWorker(jobRepository, turnExtractor, promotionService,
                meterProvider, 3, 300);
        List<AtomicConversationTurn> turns = List.of(
                new AtomicConversationTurn("turn-1", 5L, 8L, List.of("user"), "assistant"));
        when(jobRepository.claimNextDue(any())).thenReturn(job, (MemoryPromotionJobDTO) null);
        when(turnExtractor.extractTurnsInRange("session-1", 4L, 8L)).thenReturn(turns);
        when(jobRepository.markCompleted("job-1")).thenReturn(false);

        worker.poll();

        assertThat(registry.counter("chatagent.memory.promotion.jobs", "outcome", "claim_lost").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("chatagent.memory.promotion.jobs", "outcome", "completed").count())
                .isZero();
    }
}
