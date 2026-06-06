package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionSummarySegmentMapper;
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
class MyBatisChatSessionSummarySegmentRepositoryTest {

    @Mock
    private ChatSessionSummarySegmentMapper mapper;

    private MyBatisChatSessionSummarySegmentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MyBatisChatSessionSummarySegmentRepository(mapper, new ObjectMapper());
    }

    @Test
    void shouldInsertSegment() {
        ChatSessionSummarySegmentDTO segment = buildMinimalSegment("session-1", 1L, 8L);
        when(mapper.insert(any())).thenReturn(1);

        boolean inserted = repository.insert(segment);

        assertThat(inserted).isTrue();
        ArgumentCaptor<ChatSessionSummarySegmentDTO> captor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(mapper).insert(captor.capture());
        ChatSessionSummarySegmentDTO passed = captor.getValue();
        assertThat(passed.getAnchoredEntitiesJson()).isEqualTo("{}");
        assertThat(passed.getStructuredSummaryJson()).isEqualTo("{}");
        assertThat(passed.getStatus()).isEqualTo("active");
        assertThat(passed.getCreatedAt()).isNotNull();
        assertThat(passed.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldReturnFalseOnInsertConflict() {
        ChatSessionSummarySegmentDTO segment = buildMinimalSegment("session-1", 1L, 8L);
        when(mapper.insert(any())).thenReturn(0);

        boolean inserted = repository.insert(segment);

        assertThat(inserted).isFalse();
    }

    @Test
    void shouldSerializeAnchoredEntitiesOnInsert() {
        ChatSessionSummarySegmentDTO segment = ChatSessionSummarySegmentDTO.builder()
                .sessionId("session-1")
                .seqStartNo(1L)
                .seqEndNo(8L)
                .turnCount(3)
                .sourceTokenEstimate(400)
                .segmentSummary("User discussed reimbursement.")
                .anchoredEntities(Map.of("dates", List.of("2026-03-28")))
                .build();
        when(mapper.insert(any())).thenReturn(1);

        repository.insert(segment);

        ArgumentCaptor<ChatSessionSummarySegmentDTO> captor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAnchoredEntitiesJson()).contains("2026-03-28");
    }

    @Test
    void shouldHydrateAnchoredEntitiesOnFind() {
        when(mapper.selectActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .id("abc-123")
                        .sessionId("session-1")
                        .seqStartNo(1L)
                        .seqEndNo(8L)
                        .anchoredEntitiesJson("{\"dates\":[\"2026-03-28\"]}")
                        .status("active")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()
        ));

        List<ChatSessionSummarySegmentDTO> results = repository.findActiveBySessionId("session-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAnchoredEntities()).containsEntry("dates", List.of("2026-03-28"));
    }

    @Test
    void shouldFindActiveSegmentsOrdered() {
        ChatSessionSummarySegmentDTO seg1 = buildMinimalSegment("session-1", 1L, 8L);
        ChatSessionSummarySegmentDTO seg2 = buildMinimalSegment("session-1", 9L, 16L);
        when(mapper.selectActiveBySessionIdOrdered("session-1")).thenReturn(List.of(seg1, seg2));

        List<ChatSessionSummarySegmentDTO> results = repository.findActiveBySessionIdOrdered("session-1");

        assertThat(results).containsExactly(seg1, seg2);
    }

    @Test
    void shouldDeleteSegmentsBySessionId() {
        when(mapper.deleteBySessionId("session-1")).thenReturn(2);

        boolean deleted = repository.deleteBySessionId("session-1");

        assertThat(deleted).isTrue();
        verify(mapper).deleteBySessionId("session-1");
    }

    @Test
    void shouldReturnFalseWhenNoSegmentsToDelete() {
        when(mapper.deleteBySessionId("session-1")).thenReturn(0);

        boolean deleted = repository.deleteBySessionId("session-1");

        assertThat(deleted).isFalse();
    }

    private ChatSessionSummarySegmentDTO buildMinimalSegment(String sessionId, long seqStart, long seqEnd) {
        return ChatSessionSummarySegmentDTO.builder()
                .sessionId(sessionId)
                .seqStartNo(seqStart)
                .seqEndNo(seqEnd)
                .turnCount(3)
                .sourceTokenEstimate(400)
                .segmentSummary("User discussed reimbursement.")
                .build();
    }
}
