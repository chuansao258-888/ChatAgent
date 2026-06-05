package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.conversation.summary.SummaryWatermarkRange;
import com.yulong.chatagent.memory.port.MemoryExtractionLogRepository;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
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

    @Mock
    private OllamaEmbeddingClient embeddingClient;

    @Mock
    private UserMemoryIndexService indexService;

    private ObjectMapper objectMapper;

    private LongTermMemoryPromotionService service;

    private static final SummaryWatermarkRange RANGE = new SummaryWatermarkRange("session-1", 4L, 12L);
    private static final List<AtomicConversationTurn> TURNS = List.of(
            new AtomicConversationTurn("turn-1", 5L, 8L, List.of("hello"), "hi"),
            new AtomicConversationTurn("turn-2", 9L, 12L, List.of("goodbye"), "bye")
    );

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new LongTermMemoryPromotionService(
                chatSessionRepository, extractionLogRepository, memoryItemRepository,
                extractor, embeddingClient, indexService, objectMapper);
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

        ArgumentCaptor<MemoryExtractionLogDTO> logCaptor = ArgumentCaptor.forClass(MemoryExtractionLogDTO.class);
        verify(extractionLogRepository).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("processing");
        assertThat(logCaptor.getValue().getUserId()).isEqualTo("user-1");

        verify(extractor).extract(TURNS);
        verify(extractionLogRepository).updateStatus("log-1", "completed", null);
    }

    @Test
    void shouldUpsertExtractedMemoriesAndIndex() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());

        ExtractedMemory memory = new ExtractedMemory("preference", "User prefers short answers", List.of("style"));
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(memory)));

        MemoryItemDTO upsertedItem = MemoryItemDTO.builder()
                .id("mem-1").userId("user-1").type("preference").status("active")
                .content("User prefers short answers").contentHash("abc").build();
        when(memoryItemRepository.upsert(any())).thenReturn(upsertedItem);
        when(embeddingClient.embed("User prefers short answers")).thenReturn(new float[]{0.1f, 0.2f});
        when(indexService.upsertMemory(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(float[].class))).thenReturn(true);

        service.promote("session-1", RANGE, TURNS);

        verify(memoryItemRepository).upsert(any());
        verify(embeddingClient).embed("User prefers short answers");
        verify(indexService).upsertMemory(eq("mem-1"), eq("user-1"), eq("preference"),
                eq("active"), eq("User prefers short answers"), anyString(), any(float[].class));
        verify(memoryItemRepository).updateIndexStatus("mem-1", "indexed");
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
    void shouldMarkIndexFailedWhenMilvusUpsertFails() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());

        ExtractedMemory memory = new ExtractedMemory("fact", "A fact", List.of());
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(memory)));
        MemoryItemDTO upsertedItem = MemoryItemDTO.builder()
                .id("mem-1").userId("user-1").type("fact").status("active").content("A fact").build();
        when(memoryItemRepository.upsert(any())).thenReturn(upsertedItem);
        when(embeddingClient.embed("A fact")).thenReturn(new float[]{0.1f});
        when(indexService.upsertMemory(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(float[].class))).thenReturn(false);

        service.promote("session-1", RANGE, TURNS);

        verify(indexService).upsertMemory(eq("mem-1"), eq("user-1"), eq("fact"),
                eq("active"), eq("A fact"), anyString(), any(float[].class));
        verify(memoryItemRepository).updateIndexStatus("mem-1", "failed");
    }

    @Test
    void shouldMarkIndexFailedWhenEmbeddingThrows() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(extractionLogRepository.findByRange("session-1", 5L, 12L)).thenReturn(null);
        when(extractionLogRepository.insert(any())).thenReturn(
                MemoryExtractionLogDTO.builder().id("log-1").status("processing").build());

        ExtractedMemory memory = new ExtractedMemory("fact", "A fact", List.of());
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(memory)));
        MemoryItemDTO upsertedItem = MemoryItemDTO.builder()
                .id("mem-1").userId("user-1").type("fact").content("A fact").build();
        when(memoryItemRepository.upsert(any())).thenReturn(upsertedItem);
        when(embeddingClient.embed("A fact")).thenThrow(new RuntimeException("Ollama down"));

        service.promote("session-1", RANGE, TURNS);

        verify(memoryItemRepository).updateIndexStatus("mem-1", "failed");
        verify(indexService, never()).upsertMemory(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(float[].class));
    }
}
