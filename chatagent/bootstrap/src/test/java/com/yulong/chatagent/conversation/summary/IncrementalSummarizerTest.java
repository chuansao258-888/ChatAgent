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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
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
                2000,
                2,    // maxRetries
                3,    // maxConsecutiveFailures
                300   // failureBackoffSeconds
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

    // --- Phase 5 tests ---

    @Test
    void shouldRetryOnBlankOutputThenSucceed() {
        // First attempt returns blank, second (retry) returns valid JSON
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("   ")                           // attempt 1: blank
                .thenReturn("{\"summary\":\"Greeted.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}"); // attempt 2
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isTrue();
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        // Should use the successful retry output, not deterministic fallback
        assertThat(segmentCaptor.getValue().getSegmentSummary()).isEqualTo("Greeted.");
    }

    @Test
    void shouldRetryOnExceptionThenSucceed() {
        // First attempt throws, second succeeds
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Query"), "Response"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenThrow(new RuntimeException("Temporary network error"))
                .thenReturn("{\"summary\":\"Q&A done.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isTrue();
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        assertThat(segmentCaptor.getValue().getSegmentSummary()).isEqualTo("Q&A done.");
    }

    @Test
    void shouldSplitRangeWhenPromptTooLong() {
        // 4 turns — prompt too long for all 4, succeeds when split into 2+2
        AtomicConversationTurn t1 = new AtomicConversationTurn("t-1", 1L, 2L, List.of("A"), "a");
        AtomicConversationTurn t2 = new AtomicConversationTurn("t-2", 3L, 4L, List.of("B"), "b");
        AtomicConversationTurn t3 = new AtomicConversationTurn("t-3", 5L, 6L, List.of("C"), "c");
        AtomicConversationTurn t4 = new AtomicConversationTurn("t-4", 7L, 8L, List.of("D"), "d");
        List<AtomicConversationTurn> allTurns = List.of(t1, t2, t3, t4);

        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(allTurns);
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(null)                                   // initial read
                .thenReturn(ChatSessionSummaryDTO.builder()         // after first half
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("First half summary.")
                        .segmentCount(1)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        // First call (full 4 turns): prompt too long
        // Second call (first half, 2 turns): success
        // Third call (second half, 2 turns): success
        when(chatClient.prompt(anyString()).call().content())
                .thenThrow(new RuntimeException("context_length_exceeded: prompt too long"))
                .thenReturn("{\"summary\":\"AB summarized.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}")
                .thenReturn("{\"summary\":\"CD summarized.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isTrue();
        // Two segments created (one per half)
        assertThat(result.segments()).hasSize(2);
        assertThat(result.turns()).hasSize(4);
    }

    @Test
    void shouldAdvanceWatermarkOnlyThroughSuccessfulSegmentsOnPartialSplitSuccess() {
        // 4 turns — split into 2+2, first half succeeds, second half fails
        AtomicConversationTurn t1 = new AtomicConversationTurn("t-1", 1L, 2L, List.of("A"), "a");
        AtomicConversationTurn t2 = new AtomicConversationTurn("t-2", 3L, 4L, List.of("B"), "b");
        AtomicConversationTurn t3 = new AtomicConversationTurn("t-3", 5L, 6L, List.of("C"), "c");
        AtomicConversationTurn t4 = new AtomicConversationTurn("t-4", 7L, 8L, List.of("D"), "d");
        List<AtomicConversationTurn> allTurns = List.of(t1, t2, t3, t4);

        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(allTurns);
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(null)                                   // initial read
                .thenReturn(ChatSessionSummaryDTO.builder()         // after first half
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("First half.")
                        .segmentCount(1)
                        .consecutiveFailures(0)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenThrow(new RuntimeException("context_length_exceeded"))  // call 1: full range too long
                .thenReturn("{\"summary\":\"AB done.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}") // call 2: first half success
                .thenThrow(new RuntimeException("Model error"));             // call 3+: second half retries → fallback
        when(segmentRepository.insert(any())).thenReturn(true);
        // First half save succeeds, second half save fails (all 3 retry attempts), failure recording succeeds
        when(chatSessionSummaryRepository.saveOrUpdate(any()))
                .thenReturn(true)    // save 1: first half
                .thenReturn(false)   // save 2: second half attempt 1
                .thenReturn(false)   // save 3: second half attempt 2
                .thenReturn(false)   // save 4: second half attempt 3
                .thenReturn(true);   // save 5: failure state recording

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        // First half succeeded
        assertThat(result.updated()).isTrue();
        // Only first half's turns included
        assertThat(result.turns()).hasSize(2);
        assertThat(result.turns().get(0).turnId()).isEqualTo("t-1");
        assertThat(result.turns().get(1).turnId()).isEqualTo("t-2");
        // Only one segment
        assertThat(result.segments()).hasSize(1);
        // Effective range covers only first half
        assertThat(result.range().endInclusiveSeqNo()).isEqualTo(4L);
    }

    @Test
    void shouldRecordFailureWhenSaveFailsAfterOptimisticLockRetry() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(null)           // initial read
                .thenReturn(null);          // retry read
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"Hello.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        // Both save attempts fail
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(false);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isFalse();
        assertThat(result.turns()).isEmpty();
    }

    @Test
    void shouldRetryOptimisticLockConflictAndSucceed() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"Hello.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        // First two saves fail (version conflict), third succeeds (maxRetries=2 → 3 attempts)
        when(chatSessionSummaryRepository.saveOrUpdate(any()))
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isTrue();
    }

    @Test
    void shouldRecordConsecutiveFailuresWithoutBackoffBelowThreshold() {
        // consecutiveFailures goes from 1 → 2, below maxConsecutiveFailures=3
        // nextRetryAt should NOT be set yet
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(0L)
                        .synopsis("Old")
                        .segmentCount(1)
                        .consecutiveFailures(1)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()))
                .thenThrow(new RuntimeException("context_length_exceeded"));
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isFalse();
        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        ChatSessionSummaryDTO saved = captor.getValue();
        assertThat(saved.getConsecutiveFailures()).isEqualTo(2);
        // Below threshold (3): nextRetryAt should be null
        assertThat(saved.getNextRetryAt()).isNull();
        assertThat(saved.getFailedStartSeqNo()).isEqualTo(1L);
        assertThat(saved.getFailedEndSeqNo()).isEqualTo(4L);
        assertThat(saved.getLastFailureClass()).isNotNull();
    }

    @Test
    void shouldSetNextRetryAtWhenConsecutiveFailuresReachThreshold() {
        // consecutiveFailures goes from 2 → 3, reaching maxConsecutiveFailures=3
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(0L)
                        .synopsis("Old")
                        .segmentCount(1)
                        .consecutiveFailures(2)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()))
                .thenThrow(new RuntimeException("context_length_exceeded"));
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isFalse();
        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        ChatSessionSummaryDTO saved = captor.getValue();
        assertThat(saved.getConsecutiveFailures()).isEqualTo(3);
        // At threshold: nextRetryAt should be set
        assertThat(saved.getNextRetryAt()).isNotNull();
        assertThat(saved.getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void shouldClearFailureStateOnSuccess() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 5L, 8L, List.of("New message"), "New reply"
        );
        // Existing summary has failure state from previous run
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("Old synopsis")
                        .segmentCount(2)
                        .consecutiveFailures(2)
                        .failedStartSeqNo(3L)
                        .failedEndSeqNo(4L)
                        .lastFailureClass("RuntimeException")
                        .nextRetryAt(LocalDateTime.now().plusMinutes(10))
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"New content.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        ChatSessionSummaryDTO saved = captor.getValue();
        assertThat(saved.getConsecutiveFailures()).isEqualTo(0);
        assertThat(saved.getFailedStartSeqNo()).isNull();
        assertThat(saved.getFailedEndSeqNo()).isNull();
        assertThat(saved.getLastFailureClass()).isNull();
        assertThat(saved.getNextRetryAt()).isNull();
    }

    @Test
    void shouldDetectPromptTooLongFromExceptionMessage() {
        assertThat(IncrementalSummarizer.isPromptTooLong(
                new RuntimeException("context_length_exceeded"))).isTrue();
        assertThat(IncrementalSummarizer.isPromptTooLong(
                new RuntimeException("This model's maximum context length is 4096 tokens"))).isTrue();
        assertThat(IncrementalSummarizer.isPromptTooLong(
                new RuntimeException("prompt is too long for this model"))).isTrue();

        assertThat(IncrementalSummarizer.isPromptTooLong(
                new RuntimeException("Network timeout"))).isFalse();
        assertThat(IncrementalSummarizer.isPromptTooLong(
                new RuntimeException("Rate limit exceeded"))).isFalse();
    }

    @Test
    void shouldNotDuplicateSegmentsOnRetryAfterPartialSuccess() {
        // Simulate: first call splits, first half succeeds, second half fails.
        // Second call should only process the remaining range.
        AtomicConversationTurn t1 = new AtomicConversationTurn("t-1", 1L, 2L, List.of("A"), "a");
        AtomicConversationTurn t2 = new AtomicConversationTurn("t-2", 3L, 4L, List.of("B"), "b");

        // After partial success, watermark is at 4. Second call only sees turns after 4.
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of(t2));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(4L)
                        .synopsis("First half done.")
                        .segmentCount(1)
                        .consecutiveFailures(0)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"B done.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isTrue();
        assertThat(result.turns()).containsExactly(t2);
        assertThat(result.segments()).hasSize(1);
        // The retry processes only the remaining range, starting from the watermark
        ArgumentCaptor<ChatSessionSummarySegmentDTO> segmentCaptor = ArgumentCaptor.forClass(ChatSessionSummarySegmentDTO.class);
        verify(segmentRepository).insert(segmentCaptor.capture());
        assertThat(segmentCaptor.getValue().getSeqStartNo()).isEqualTo(3L);
    }

    @Test
    void shouldPreserveExistingAnchoredEntitiesOnFailure() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        // Existing summary with anchored entities and structured summary
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .summarizedUntilSeqNo(0L)
                        .synopsis("Existing synopsis")
                        .structuredSummaryJson("{\"summary\":\"old\"}")
                        .anchoredEntities(Map.of("dates", List.of("2026-01-01"), "orderIds", List.of("AB-1234")))
                        .segmentCount(2)
                        .consecutiveFailures(2)
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()))
                .thenThrow(new RuntimeException("context_length_exceeded"));
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        ChatSessionSummaryDTO saved = captor.getValue();
        // Anchored entities and structured summary must be preserved
        assertThat(saved.getAnchoredEntities()).containsEntry("dates", List.of("2026-01-01"));
        assertThat(saved.getAnchoredEntities()).containsEntry("orderIds", List.of("AB-1234"));
        assertThat(saved.getStructuredSummaryJson()).isEqualTo("{\"summary\":\"old\"}");
        assertThat(saved.getSynopsis()).isEqualTo("Existing synopsis");
        assertThat(saved.getSegmentCount()).isEqualTo(2);
    }

    @Test
    void shouldFailAfterMaxRetriesSaveAttempts() {
        AtomicConversationTurn turn = new AtomicConversationTurn(
                "turn-1", 1L, 4L, List.of("Hello"), "Hi"
        );
        when(summaryWatermarkService.resolvePendingRange("session-1", 4L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 4L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 4L))
                .thenReturn(List.of(turn));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("{\"summary\":\"Hello.\",\"facts\":[],\"decisions\":[],\"open_tasks\":[],\"entities\":{}}");
        when(segmentRepository.insert(any())).thenReturn(true);
        // All 3 save attempts (maxRetries=2 → maxRetries+1=3) fail
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(false);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 4L);

        assertThat(result.updated()).isFalse();
        assertThat(result.turns()).isEmpty();
    }
}
