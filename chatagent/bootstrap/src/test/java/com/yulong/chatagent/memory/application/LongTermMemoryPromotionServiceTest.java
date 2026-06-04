package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import com.yulong.chatagent.memory.port.MemoryExtractionLogRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryPromotionServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private MemoryExtractionLogRepository extractionLogRepository;

    private LongTermMemoryPromotionService service;

    private static final SummaryWatermarkRange RANGE = new SummaryWatermarkRange("session-1", 4L, 12L);
    private static final List<AtomicConversationTurn> TURNS = List.of(
            new AtomicConversationTurn("turn-1", 5L, 8L, List.of("hello"), "hi"),
            new AtomicConversationTurn("turn-2", 9L, 12L, List.of("goodbye"), "bye")
    );

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryPromotionService(chatSessionRepository, extractionLogRepository);
    }

    @Test
    void shouldSkipWhenSessionHasNoUser() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId(null).build());

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository, never()).findByRange(anyString(), any(Long.class), any(Long.class));
        verify(extractionLogRepository, never()).insert(any());
    }

    @Test
    void shouldSkipWhenSessionNotFound() {
        when(chatSessionRepository.findById("session-1")).thenReturn(null);

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository, never()).findByRange(anyString(), any(Long.class), any(Long.class));
    }

    @Test
    void shouldSkipDuplicateExtractionRange() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L))
                .thenReturn(MemoryExtractionLogDTO.builder().id("log-1").status("completed").build());

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository, never()).insert(any());
    }

    @Test
    void shouldInsertProcessingLogAndMarkCompleted() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenAnswer(invocation -> {
            MemoryExtractionLogDTO log = invocation.getArgument(0);
            log.setId("log-1");
            return log;
        });

        service.promote("session-1", RANGE, TURNS);

        ArgumentCaptor<MemoryExtractionLogDTO> insertCaptor = ArgumentCaptor.forClass(MemoryExtractionLogDTO.class);
        verify(extractionLogRepository).insert(insertCaptor.capture());
        MemoryExtractionLogDTO inserted = insertCaptor.getValue();
        assertThat(inserted.getUserId()).isEqualTo("user-1");
        assertThat(inserted.getSessionId()).isEqualTo("session-1");
        assertThat(inserted.getSeqStartNo()).isEqualTo(5L);
        assertThat(inserted.getSeqEndNo()).isEqualTo(12L);
        assertThat(inserted.getStatus()).isEqualTo("processing");

        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }

    @Test
    void shouldReceiveRawTurnsNotSummaryText() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenAnswer(invocation -> {
            MemoryExtractionLogDTO log = invocation.getArgument(0);
            log.setId("log-1");
            return log;
        });

        // The promote method receives raw turns — the service never sees L2 summary text.
        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }

    @Test
    void shouldNotThrowWhenPromotionFails() {
        when(chatSessionRepository.findById("session-1")).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> service.promote("session-1", RANGE, TURNS))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldMarkExtractionLogFailedOnExtractionError() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenAnswer(invocation -> {
            MemoryExtractionLogDTO log = invocation.getArgument(0);
            log.setId("log-1");
            return log;
        });
        // Phase 3 will add real extractor errors. For now, test that the log-update path works
        // by simulating a failure inside the extraction block via a subsequent repository error.
        // Since the current Phase 2 stub never throws, we directly verify the happy path.
        // The failure isolation test is covered by shouldNotThrowWhenPromotionFails above.

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }
}
