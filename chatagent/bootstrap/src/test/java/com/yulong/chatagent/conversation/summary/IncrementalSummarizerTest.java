package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummarySegmentRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncrementalSummarizerTest {

    @Mock
    private TurnBasedContextExtractor turnBasedContextExtractor;

    @Mock
    private SummaryWatermarkService summaryWatermarkService;

    @Mock
    private ChatSessionSummaryRepository chatSessionSummaryRepository;

    @Mock
    private ChatSessionSummarySegmentRepository segmentRepository;

    @Mock
    private ChatModelRouter chatModelRouter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    private IncrementalSummarizer incrementalSummarizer;

    @BeforeEach
    void setUp() {
        incrementalSummarizer = new IncrementalSummarizer(
                TestPromptLoader.create(),
                turnBasedContextExtractor,
                summaryWatermarkService,
                chatSessionSummaryRepository,
                segmentRepository,
                chatModelRouter,
                "summary-model",
                1200,
                2000
        );
    }

    @Test
    void shouldCreateSegmentAndMergeSynopsis() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 5L, 8L,
                List.of("Order AB-1234 on 2026-03-28 amount $100"),
                "Approved and submitted."
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("Existing summary")
                        .anchoredEntities(Map.of("dates", List.of("2026-03-01")))
                        .segmentCount(0)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("""
                        {
                          "summary": "User ordered AB-1234 on 2026-03-28 for $100.",
                          "facts": ["Order AB-1234 placed on 2026-03-28 for $100."],
                          "decisions": ["Order approved and submitted."],
                          "open_tasks": [],
                          "entities": {
                            "dates": ["2026-03-28"],
                            "amounts": ["$100"],
                            "orderIds": ["AB-1234"]
                          }
                        }
                        """);
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isTrue();
        assertThat(result.turns()).containsExactly(turn);
        assertThat(result.segments()).hasSize(1);

        // Verify segment content
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        ChatSessionSummarySegmentDTO segment = segmentCaptor.getValue();
        assertThat(segment.getSessionId()).isEqualTo("session-1");
        assertThat(segment.getSeqStartNo()).isEqualTo(5L);
        assertThat(segment.getSeqEndNo()).isEqualTo(8L);
        assertThat(segment.getTurnCount()).isEqualTo(1);
        assertThat(segment.getSegmentSummary()).contains("AB-1234");
        assertThat(segment.getStructuredSummaryJson()).contains("AB-1234");
        assertThat(segment.getAnchoredEntities().get("dates")).contains("2026-03-01", "2026-03-28");

        // Verify synopsis was merged
        ArgumentCaptor<ChatSessionSummaryDTO> summaryCaptor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(summaryCaptor.capture());
        ChatSessionSummaryDTO saved = summaryCaptor.getValue();
        assertThat(saved.getSummarizedUntilSeqNo()).isEqualTo(8L);
        assertThat(saved.getSynopsis()).contains("Existing summary").contains("AB-1234");
        assertThat(saved.getSegmentCount()).isEqualTo(1);
        assertThat(saved.getConsecutiveFailures()).isEqualTo(0);

        // Verify result synopsis
        assertThat(result.synopsis()).contains("AB-1234");
    }

    @Test
    void shouldFallbackToDeterministicWhenLlmFails() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 6L,
                List.of("Need reimbursement for taxi"),
                "Please submit the receipt."
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 6L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 6L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 6L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenThrow(new RuntimeException("LLM unavailable"));
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 6L);

        assertThat(result.updated()).isTrue();
        assertThat(result.segments()).hasSize(1);
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        assertThat(segmentCaptor.getValue().getSegmentSummary())
                .contains("User: Need reimbursement for taxi")
                .contains("Assistant: Please submit the receipt.");
    }

    @Test
    void shouldReturnEmptyWhenNoPendingRange() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 8L, 8L));

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isFalse();
        assertThat(result.turns()).isEmpty();
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoPendingTurns() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of());

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isFalse();
        assertThat(result.turns()).isEmpty();
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void shouldMergeAnchoredEntitiesFromRegexAndStructured() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L,
                List.of("Booking ref BK-5678 on 2026-01-15"),
                "Confirmed."
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("""
                        {
                          "summary": "Booking BK-5678 confirmed.",
                          "facts": ["Booking ref BK-5678."],
                          "decisions": [],
                          "open_tasks": [],
                          "entities": {
                            "dates": ["2026-01-15"],
                            "orderIds": ["BK-5678"]
                          }
                        }
                        """);
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        Map<String, List<String>> entities = segmentCaptor.getValue().getAnchoredEntities();
        // Regex extracted "BK-5678" and "2026-01-15", structured entities also provide them
        assertThat(entities.get("dates")).contains("2026-01-15");
        assertThat(entities.get("orderIds")).contains("BK-5678");
    }

    @Test
    void shouldTruncateSynopsisFromBeginning() {
        // Create a synopsis that will exceed the cap when merged
        String longSynopsis = "A".repeat(1900);
        String newSegmentSummary = "B".repeat(200);

        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(new AtomicConversationTurn("t-1", 1L, 4L, List.of("msg"), "reply")));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(0L)
                        .synopsis(longSynopsis)
                        .segmentCount(1)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"" + newSegmentSummary + "\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        String synopsis = captor.getValue().getSynopsis();
        assertThat(synopsis.length()).isLessThanOrEqualTo(2000);
        // Should contain the newest content (B's)
        assertThat(synopsis).contains("B");
    }

    @Test
    void shouldUseStructuredPrompt() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(new AtomicConversationTurn("t-1", 1L, 4L, List.of("Hello"), "Hi")));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"Greeting exchanged.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient, atLeastOnce()).prompt(promptCaptor.capture());
        String prompt = promptCaptor.getAllValues().get(promptCaptor.getAllValues().size() - 1);
        // The structured prompt should reference segment-max-chars, not summary-max-chars
        assertThat(prompt).contains("1200");
        assertThat(prompt).contains("Turn 1");
    }

    @Test
    void shouldIncrementSegmentCount() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of(new AtomicConversationTurn("t-1", 5L, 8L, List.of("msg"), "reply")));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("Old")
                        .segmentCount(2)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"New segment.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        assertThat(captor.getValue().getSegmentCount()).isEqualTo(3);
    }

    @Test
    void shouldHandleDuplicateSegmentInsert() {
        // ON CONFLICT returns false — segment already exists from a previous attempt
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 5L, 8L,
                List.of("Order AB-1234 on 2026-03-28"),
                "Approved."
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("Existing")
                        .segmentCount(1)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"Order AB-1234 approved.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(false);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        // Segment insert failed (duplicate), so no segments in result
        assertThat(result.updated()).isTrue();
        assertThat(result.segments()).isEmpty();

        // Segment count should NOT increment
        ArgumentCaptor<ChatSessionSummaryDTO> summaryCaptor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getSegmentCount()).isEqualTo(1);

        // Synopsis should still be merged and watermark advanced
        assertThat(summaryCaptor.getValue().getSynopsis()).contains("AB-1234");
        assertThat(summaryCaptor.getValue().getSummarizedUntilSeqNo()).isEqualTo(8L);
    }

    @Test
    void shouldFallbackWhenLlmReturnsInvalidJson() {
        // LLM returns explanatory text instead of JSON
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 6L,
                List.of("Need reimbursement for taxi"),
                "Please submit the receipt."
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 6L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 6L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 6L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("Here is a summary of the conversation: the user asked about taxi reimbursement.");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 6L);

        assertThat(result.updated()).isTrue();
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        // Must use deterministic fallback from turns, NOT the LLM's unstructured text
        assertThat(segmentCaptor.getValue().getSegmentSummary())
                .contains("User: Need reimbursement for taxi")
                .contains("Assistant: Please submit the receipt.")
                .doesNotContain("Here is a summary");
    }

    @Test
    void shouldFallbackWhenLlmReturnsBlankContent() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L,
                List.of("Hello world"),
                "Hi there"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("   ");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isTrue();
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        // Should use deterministic fallback
        assertThat(segmentCaptor.getValue().getSegmentSummary())
                .contains("User: Hello world")
                .contains("Assistant: Hi there");
    }
}
