package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
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
                chatModelRouter,
                "summary-model",
                500
        );
    }

    @Test
    void shouldPersistMergedSummaryAndAnchors() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 4L, 8L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 8L))
                .thenReturn(List.of(new AtomicConversationTurn(
                        "turn-1",
                        5L,
                        8L,
                        List.of("Order AB-1234 on 2026-03-28 amount $100"),
                        "Approved and submitted."
                )));
        when(chatSessionSummaryRepository.findBySessionId("session-1"))
                .thenReturn(ChatSessionSummaryDTO.builder()
                        .sessionId("session-1")
                        .lastSeqNo(4L)
                        .summary("Existing summary")
                        .anchoredEntities(Map.of("dates", List.of("2026-03-01")))
                        .build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content())
                .thenReturn("Updated summary with AB-1234 on 2026-03-28 and $100.");
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        boolean summarized = incrementalSummarizer.summarize("session-1", 8L);

        assertThat(summarized).isTrue();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient, atLeastOnce()).prompt(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(promptCaptor.getAllValues().size() - 1))
                .contains("under 500 characters");
        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        ChatSessionSummaryDTO saved = captor.getValue();
        assertThat(saved.getLastSeqNo()).isEqualTo(8L);
        assertThat(saved.getSummary()).contains("AB-1234");
        assertThat(saved.getAnchoredEntities().get("dates")).contains("2026-03-01", "2026-03-28");
        assertThat(saved.getAnchoredEntities().get("amounts")).contains("$100");
        assertThat(saved.getAnchoredEntities().get("orderIds")).contains("AB-1234");
    }

    @Test
    void shouldReturnRawTurnsViaSummarizeWithDetails() {
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
                        .sessionId("session-1").lastSeqNo(4L).summary("Old").build());
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("Updated summary.");
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isTrue();
        assertThat(result.range()).isEqualTo(new SummaryWatermarkRange("session-1", 4L, 8L));
        assertThat(result.turns()).containsExactly(turn);
    }

    @Test
    void shouldReturnEmptyTurnsWhenNoPendingRange() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 8L))
                .thenReturn(new SummaryWatermarkRange("session-1", 8L, 8L));

        SummaryResult result = incrementalSummarizer.summarizeWithDetails("session-1", 8L);

        assertThat(result.updated()).isFalse();
        assertThat(result.turns()).isEmpty();
    }

    @Test
    void shouldFallbackToDeterministicSummaryWhenLlmFails() {
        when(summaryWatermarkService.resolvePendingRange("session-1", 6L))
                .thenReturn(new SummaryWatermarkRange("session-1", 0L, 6L));
        when(turnBasedContextExtractor.extractPendingTurns("session-1", 6L))
                .thenReturn(List.of(new AtomicConversationTurn(
                        "turn-1",
                        1L,
                        6L,
                        List.of("Need reimbursement for taxi"),
                        "Please submit the receipt."
                )));
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(null);
        when(chatModelRouter.route("summary-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenThrow(new RuntimeException("LLM unavailable"));
        when(chatSessionSummaryRepository.saveOrUpdate(any())).thenReturn(true);

        boolean summarized = incrementalSummarizer.summarize("session-1", 6L);

        assertThat(summarized).isTrue();
        ArgumentCaptor<ChatSessionSummaryDTO> captor = ArgumentCaptor.forClass(ChatSessionSummaryDTO.class);
        verify(chatSessionSummaryRepository).saveOrUpdate(captor.capture());
        assertThat(captor.getValue().getSummary())
                .contains("User: Need reimbursement for taxi")
                .contains("Assistant: Please submit the receipt.");
    }
}
