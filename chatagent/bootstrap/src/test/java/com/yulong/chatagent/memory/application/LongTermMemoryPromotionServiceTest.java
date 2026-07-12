package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import com.yulong.chatagent.memory.port.MemoryItemRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.MemoryItemDTO;
import com.yulong.chatagent.support.dto.MemoryPromotionJobDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryPromotionServiceTest {
    @Mock private MemoryItemRepository memoryItemRepository;
    @Mock private LongTermMemoryExtractor extractor;
    @Mock private OllamaEmbeddingClient embeddingClient;
    @Mock private UserMemoryIndexService indexService;

    private LongTermMemoryPromotionService service;

    private static final MemoryPromotionJobDTO JOB = MemoryPromotionJobDTO.builder()
            .id("job-1").userId("user-1").sessionId("session-1")
            .seqStartNo(5L).seqEndNo(8L).attempts(1).build();
    private static final List<AtomicConversationTurn> TURNS = List.of(
            new AtomicConversationTurn("turn-1", 5L, 8L,
                    List.of("raw user message"), "visible assistant conclusion"));

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryPromotionService(
                memoryItemRepository, extractor, embeddingClient, indexService, new ObjectMapper());
    }

    @Test
    void shouldPromoteRawTurnsAndIndexMemory() {
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(
                new ExtractedMemory("preference", "User prefers concise answers", List.of("style")))));
        MemoryItemDTO saved = MemoryItemDTO.builder().id("memory-1").userId("user-1")
                .type("preference").status("active").content("User prefers concise answers").build();
        when(memoryItemRepository.upsert(any())).thenReturn(saved);
        when(embeddingClient.embed(saved.getContent())).thenReturn(new float[]{0.1f});
        when(indexService.upsertMemory(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(float[].class))).thenReturn(true);

        int count = service.promote(JOB, TURNS);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<List<AtomicConversationTurn>> turnsCaptor = ArgumentCaptor.forClass(List.class);
        verify(extractor).extract(turnsCaptor.capture());
        assertThat(turnsCaptor.getValue()).isEqualTo(TURNS);
        verify(indexService).upsertMemory(eq("memory-1"), eq("user-1"), eq("preference"),
                eq("active"), eq(saved.getContent()), anyString(), any(float[].class));
        verify(memoryItemRepository).updateIndexStatus("memory-1", "indexed");
    }

    @Test
    void shouldReturnZeroForSuccessfulEmptyExtraction() {
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of()));

        assertThat(service.promote(JOB, TURNS)).isZero();

        verify(memoryItemRepository, never()).upsert(any());
    }

    @Test
    void shouldPropagateExtractorFailureForDurableRetry() {
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.failure());

        assertThatThrownBy(() -> service.promote(JOB, TURNS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldMarkIndexFailedAndPropagateForRetry() {
        when(extractor.extract(TURNS)).thenReturn(ExtractionResult.success(List.of(
                new ExtractedMemory("fact", "A fact", List.of()))));
        MemoryItemDTO saved = MemoryItemDTO.builder().id("memory-1").userId("user-1")
                .type("fact").status("active").content("A fact").build();
        when(memoryItemRepository.upsert(any())).thenReturn(saved);
        when(embeddingClient.embed("A fact")).thenReturn(new float[]{0.1f});
        when(indexService.upsertMemory(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(float[].class))).thenReturn(false);

        assertThatThrownBy(() -> service.promote(JOB, TURNS))
                .isInstanceOf(IllegalStateException.class);

        verify(memoryItemRepository).updateIndexStatus("memory-1", "failed");
    }
}
