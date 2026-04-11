package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyBatisChatSessionSummaryRepositoryTest {

    @Mock
    private ChatSessionSummaryMapper chatSessionSummaryMapper;

    private MyBatisChatSessionSummaryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MyBatisChatSessionSummaryRepository(chatSessionSummaryMapper, new ObjectMapper());
    }

    @Test
    void shouldInsertNewSummaryWithInitialVersion() {
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .lastSeqNo(8L)
                .summary("Rolling summary")
                .anchoredEntities(Map.of("dates", List.of("2026-03-28")))
                .build();
        when(chatSessionSummaryMapper.selectBySessionId("session-1")).thenReturn(null);
        when(chatSessionSummaryMapper.insert(any())).thenReturn(1);

        boolean saved = repository.saveOrUpdate(summary);

        assertThat(saved).isTrue();
        assertThat(summary.getVersion()).isEqualTo(0);
        assertThat(summary.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldSerializeNullAnchoredEntitiesAsEmptyJsonObject() {
        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .lastSeqNo(2L)
                .summary("Summary")
                .anchoredEntities(null)
                .build();
        when(chatSessionSummaryMapper.selectBySessionId("session-1")).thenReturn(null);
        when(chatSessionSummaryMapper.insert(any())).thenReturn(1);

        repository.saveOrUpdate(summary);

        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryMapper).insert(captor.capture());
        assertThat(captor.getValue().getAnchoredEntitiesJson()).isEqualTo("{}");
    }

    @Test
    void shouldUpdateSummaryWithOptimisticVersionIncrement() {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(10);
        when(chatSessionSummaryMapper.selectBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .lastSeqNo(8L)
                        .summary("Old summary")
                        .anchoredEntitiesJson("")
                        .version(3)
                        .createdAt(createdAt)
                        .updatedAt(createdAt)
                        .build()
        );
        when(chatSessionSummaryMapper.updateBySessionIdAndVersion(any())).thenReturn(1);

        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .lastSeqNo(12L)
                .summary("New summary")
                .anchoredEntities(Map.of("amounts", List.of("100")))
                .build();

        boolean updated = repository.saveOrUpdate(summary);

        assertThat(updated).isTrue();
        assertThat(summary.getVersion()).isEqualTo(4);
        assertThat(summary.getCreatedAt()).isEqualTo(createdAt);
        verify(chatSessionSummaryMapper).updateBySessionIdAndVersion(any());
    }

    @Test
    void shouldReturnFalseWhenOptimisticLockUpdateConflicts() {
        when(chatSessionSummaryMapper.selectBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .lastSeqNo(8L)
                        .summary("Old summary")
                        .anchoredEntitiesJson("{}")
                        .version(3)
                        .createdAt(LocalDateTime.now().minusMinutes(10))
                        .updatedAt(LocalDateTime.now().minusMinutes(5))
                        .build()
        );
        when(chatSessionSummaryMapper.updateBySessionIdAndVersion(any())).thenReturn(0);

        ChatSessionSummaryDTO summary = ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .lastSeqNo(12L)
                .summary("New summary")
                .anchoredEntities(Map.of())
                .build();

        boolean updated = repository.saveOrUpdate(summary);

        assertThat(updated).isFalse();
        assertThat(summary.getVersion()).isEqualTo(3);
    }

    @Test
    void shouldDeleteSummaryBySessionId() {
        when(chatSessionSummaryMapper.deleteBySessionId("session-1")).thenReturn(1);

        boolean deleted = repository.deleteBySessionId("session-1");

        assertThat(deleted).isTrue();
        verify(chatSessionSummaryMapper).deleteBySessionId("session-1");
    }
}
