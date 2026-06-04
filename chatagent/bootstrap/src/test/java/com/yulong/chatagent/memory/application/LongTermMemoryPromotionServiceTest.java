package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import com.yulong.chatagent.memory.port.MemoryExtractionLogRepository;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.MemoryExtractionLogDTO;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryPromotionServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private MemoryExtractionLogRepository extractionLogRepository;

    @Mock
    private MemoryItemRepository memoryItemRepository;

    @Mock
    private LongTermMemoryExtractor extractor;

    private LongTermMemoryPromotionService service;

    private static final SummaryWatermarkRange RANGE = new SummaryWatermarkRange("session-1", 4L, 12L);
    private static final List<AtomicConversationTurn> TURNS = List.of(
            new AtomicConversationTurn("turn-1", 5L, 8L, List.of("hello"), "hi"),
            new AtomicConversationTurn("turn-2", 9L, 12L, List.of("goodbye"), "bye")
    );

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryPromotionService(
                chatSessionRepository, extractionLogRepository, memoryItemRepository, extractor);
    }

    @Test
    void shouldSkipWhenSessionHasNoUser() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId(null).build());

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository, never()).findByRange(anyString(), anyLong(), anyLong());
        verify(extractionLogRepository, never()).insert(any());
    }

    @Test
    void shouldSkipWhenSessionNotFound() {
        when(chatSessionRepository.findById("session-1")).thenReturn(null);

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository, never()).findByRange(anyString(), anyLong(), anyLong());
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
    void shouldInsertProcessingLogAndCallExtractor() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of()));

        service.promote("session-1", RANGE, TURNS);

        // Verify extraction log was inserted with "processing" status.
        ArgumentCaptor<MemoryExtractionLogDTO> logCaptor = ArgumentCaptor.forClass(MemoryExtractionLogDTO.class);
        verify(extractionLogRepository).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("processing");
        assertThat(logCaptor.getValue().getUserId()).isEqualTo("user-1");

        // Verify extractor was called.
        verify(extractor).extract(TURNS);

        // Verify log updated to completed.
        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }

    @Test
    void shouldUpsertExtractedMemories() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());

        ExtractedMemory memory = new ExtractedMemory("preference", "User prefers short answers", List.of("style"));
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(memory)));

        service.promote("session-1", RANGE, TURNS);

        // Verify memory was upserted with correct hash.
        ArgumentCaptor<MemoryItemDTO> itemCaptor = ArgumentCaptor.forClass(MemoryItemDTO.class);
        verify(memoryItemRepository).upsert(itemCaptor.capture());
        MemoryItemDTO item = itemCaptor.getValue();
        assertThat(item.getUserId()).isEqualTo("user-1");
        assertThat(item.getType()).isEqualTo("preference");
        assertThat(item.getContent()).isEqualTo("User prefers short answers");
        assertThat(item.getContentHash()).hasSize(64);
        assertThat(item.getStatus()).isEqualTo("active");
        assertThat(item.getIndexStatus()).isEqualTo("pending");
        assertThat(item.getSource()).containsEntry("session_id", "session-1");

        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }

    @Test
    void shouldMarkLogFailedOnExtractorFailure() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.failure());

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository).updateStatus("log-1", "failed", "extractor returned failure");
        verify(memoryItemRepository, never()).upsert(any());
    }

    @Test
    void shouldMarkLogFailedOnExtractionError() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());
        when(extractor.extract(TURNS)).thenThrow(new RuntimeException("LLM timeout"));

        service.promote("session-1", RANGE, TURNS);

        verify(extractionLogRepository).updateStatus(eq("log-1"), eq("failed"), anyString());
        verify(memoryItemRepository, never()).upsert(any());
    }

    @Test
    void shouldNotThrowWhenPromotionFails() {
        when(chatSessionRepository.findById("session-1")).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> service.promote("session-1", RANGE, TURNS))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotUpsertWhenExtractorReturnsEmptyMemories() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of()));

        service.promote("session-1", RANGE, TURNS);

        verify(memoryItemRepository, never()).upsert(any());
        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }

    @Test
    void shouldDedupeSameContentViaHash() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());

        // Same content extracted twice with different casing/whitespace should produce same hash.
        ExtractedMemory m1 = new ExtractedMemory("fact", "User works at NTU", List.of());
        ExtractedMemory m2 = new ExtractedMemory("fact", "user  works  at  ntu", List.of());
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(m1, m2)));

        service.promote("session-1", RANGE, TURNS);

        // Both should be upserted — the ON CONFLICT in the DB handles the dedup.
        verify(memoryItemRepository, times(2)).upsert(any());
    }
}
