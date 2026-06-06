package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSessionSummaryResolverTest {

    @Mock
    private ChatSessionSummaryRepository chatSessionSummaryRepository;

    @Mock
    private ChatSessionSummarySegmentRepository segmentRepository;

    private AgentSessionSummaryResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentSessionSummaryResolver(chatSessionSummaryRepository, segmentRepository, 3);
    }

    @Test
    void shouldReturnEmptyWhenNoSummaryExists() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of());

        String result = resolver.resolve("session-1");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSynopsisIsBlankAndNoSegments() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of());

        String result = resolver.resolve("session-1");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnSynopsisWhenPresent() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("User discussed reimbursement process")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of());

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("User discussed reimbursement process");
    }

    @Test
    void shouldReturnTrimmedSynopsis() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("  User discussed reimbursement  ")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of());

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("User discussed reimbursement");
    }

    @Test
    void shouldReturnEmptyWhenSessionIdIsBlank() {
        String result = resolver.resolve("");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSessionIdIsNull() {
        String result = resolver.resolve(null);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldAppendLatestSegmentsToSynopsis() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("Session synopsis")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(9L).seqEndNo(12L)
                        .segmentSummary("Latest segment detail").build(),
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(5L).seqEndNo(8L)
                        .segmentSummary("Older segment detail").build()
        ));

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("Session synopsis\n\n[Segment 9..12] Latest segment detail\n\n[Segment 5..8] Older segment detail");
    }

    @Test
    void shouldBoundSegmentsByRuntimeMaxSegments() {
        AgentSessionSummaryResolver limitedResolver = new AgentSessionSummaryResolver(
                chatSessionSummaryRepository, segmentRepository, 1);

        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("Synopsis")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(9L).seqEndNo(12L)
                        .segmentSummary("Latest").build(),
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(5L).seqEndNo(8L)
                        .segmentSummary("Older").build()
        ));

        String result = limitedResolver.resolve("session-1");

        assertThat(result).contains("[Segment 9..12] Latest");
        assertThat(result).doesNotContain("[Segment 5..8]");
    }

    @Test
    void shouldSkipSegmentsWithBlankSummary() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("Synopsis")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(9L).seqEndNo(12L)
                        .segmentSummary("").build(),
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(5L).seqEndNo(8L)
                        .segmentSummary("Valid detail").build()
        ));

        String result = resolver.resolve("session-1");

        assertThat(result).doesNotContain("[Segment 9..12]");
        assertThat(result).contains("[Segment 5..8] Valid detail");
    }

    @Test
    void shouldSkipSegmentsWhenRuntimeMaxSegmentsIsZero() {
        AgentSessionSummaryResolver noSegmentsResolver = new AgentSessionSummaryResolver(
                chatSessionSummaryRepository, segmentRepository, 0);

        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("Synopsis only")
                        .build()
        );

        String result = noSegmentsResolver.resolve("session-1");

        assertThat(result).isEqualTo("Synopsis only");
    }

    // P2 regression: blank synopsis + active segment → segment text is returned
    @Test
    void shouldReturnSegmentsWhenSynopsisIsBlankButSegmentsExist() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(5L).seqEndNo(8L)
                        .segmentSummary("Segment without synopsis").build()
        ));

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("[Segment 5..8] Segment without synopsis");
    }

    // P2 regression: null summary row + active segment → segment text is returned
    @Test
    void shouldReturnSegmentsWhenNoSummaryRowButSegmentsExist() {
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(1L).seqEndNo(4L)
                        .segmentSummary("Orphan segment").build()
        ));

        String result = resolver.resolve("session-1");

        assertThat(result).isEqualTo("[Segment 1..4] Orphan segment");
    }

    // P3 regression: blank newest segment does not crowd out older valid segment
    @Test
    void shouldNotLetBlankSegmentCrowdOutValidSegmentWithinLimit() {
        AgentSessionSummaryResolver limitedResolver = new AgentSessionSummaryResolver(
                chatSessionSummaryRepository, segmentRepository, 1);

        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(
                ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .synopsis("Synopsis")
                        .build()
        );
        when(segmentRepository.findActiveBySessionId("session-1")).thenReturn(List.of(
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(9L).seqEndNo(12L)
                        .segmentSummary("").build(),
                ChatSessionSummarySegmentDTO.builder()
                        .sessionId("session-1").seqStartNo(5L).seqEndNo(8L)
                        .segmentSummary("Older valid").build()
        ));

        String result = limitedResolver.resolve("session-1");

        // The blank newest segment is skipped; the older valid segment fills the slot
        assertThat(result).contains("[Segment 5..8] Older valid");
        assertThat(result).doesNotContain("[Segment 9..12]");
    }
}
