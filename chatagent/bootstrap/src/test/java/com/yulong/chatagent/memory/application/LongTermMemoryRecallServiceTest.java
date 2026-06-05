package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryRecallServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private UserMemoryIndexService indexService;

    @Mock
    private OllamaEmbeddingClient embeddingClient;

    private LongTermMemoryRecallService service;

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryRecallService(chatSessionRepository, indexService, embeddingClient, true, 3);
    }

    @Test
    void shouldReturnEmptyWithoutExternalCallsWhenL3Disabled() {
        LongTermMemoryRecallService disabledService = new LongTermMemoryRecallService(
                chatSessionRepository, indexService, embeddingClient, false, 3);

        String result = disabledService.recall("session-1", "hello");

        assertThat(result).isEmpty();
        verify(chatSessionRepository, never()).findById(anyString());
        verify(embeddingClient, never()).embed(anyString());
        verify(indexService, never()).search(anyString(), any(float[].class), anyInt());
    }

    @Test
    void shouldReturnEmptyWhenQueryIsBlank() {
        String result = service.recall("session-1", "");
        assertThat(result).isEmpty();
        verify(chatSessionRepository, never()).findById(anyString());
    }

    @Test
    void shouldReturnEmptyWhenQueryIsNull() {
        String result = service.recall("session-1", null);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSessionHasNoUser() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId(null).build());

        String result = service.recall("session-1", "hello");

        assertThat(result).isEmpty();
        verify(embeddingClient, never()).embed(anyString());
    }

    @Test
    void shouldReturnEmptyWhenSessionNotFound() {
        when(chatSessionRepository.findById("session-1")).thenReturn(null);

        String result = service.recall("session-1", "hello");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoMemoriesFound() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(embeddingClient.embed("hello")).thenReturn(new float[]{0.1f, 0.2f});
        when(indexService.search("user-1", new float[]{0.1f, 0.2f}, 3)).thenReturn(List.of());

        String result = service.recall("session-1", "hello");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnFormattedMemories() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(embeddingClient.embed("hello")).thenReturn(new float[]{0.1f});
        when(indexService.search(eq("user-1"), any(float[].class), eq(3))).thenReturn(List.of(
                new UserMemorySearchHit("mem-1", "preference", "User prefers short answers", 0.95),
                new UserMemorySearchHit("mem-2", "fact", "User works at NTU", 0.88)
        ));

        String result = service.recall("session-1", "hello");

        assertThat(result).isEqualTo(
                "- preference: User prefers short answers\n" +
                "- fact: User works at NTU");
    }

    @Test
    void shouldReturnEmptyOnEmbeddingFailure() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(embeddingClient.embed("hello")).thenThrow(new RuntimeException("Ollama down"));

        String result = service.recall("session-1", "hello");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnSearchFailure() {
        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(embeddingClient.embed("hello")).thenReturn(new float[]{0.1f});
        when(indexService.search(eq("user-1"), any(float[].class), anyInt()))
                .thenThrow(new RuntimeException("Milvus down"));

        String result = service.recall("session-1", "hello");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldUseConfiguredTopK() {
        LongTermMemoryRecallService customService = new LongTermMemoryRecallService(
                chatSessionRepository, indexService, embeddingClient, true, 5);

        when(chatSessionRepository.findById("session-1")).thenReturn(
                ChatSessionDTO.builder().id("session-1").userId("user-1").build());
        when(embeddingClient.embed("hello")).thenReturn(new float[]{0.1f});
        when(indexService.search(eq("user-1"), any(float[].class), eq(5))).thenReturn(List.of());

        customService.recall("session-1", "hello");

        verify(indexService).search(eq("user-1"), any(float[].class), eq(5));
    }
}
