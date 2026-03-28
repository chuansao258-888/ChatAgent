package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TurnBasedContextExtractorTest {

    @Mock
    private SummaryWatermarkService summaryWatermarkService;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private TurnBasedContextExtractor turnBasedContextExtractor;

    @BeforeEach
    void setUp() {
        turnBasedContextExtractor = new TurnBasedContextExtractor(summaryWatermarkService, chatMessageRepository);
    }

    @Test
    void shouldDelegateTurnCountingToRepository() {
        when(chatMessageRepository.countTurnsBySessionId("session-1")).thenReturn(6L);

        assertThat(turnBasedContextExtractor.countTurns("session-1")).isEqualTo(6L);
    }

    @Test
    void shouldExtractAtomicTurnsAndFilterToolNoise() {
        when(summaryWatermarkService.loadPendingMessages("session-1", 6L)).thenReturn(List.of(
                ChatMessageDTO.builder()
                        .id("m1")
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("Order AB-1234 on 2026-03-28")
                        .seqNo(1L)
                        .build(),
                ChatMessageDTO.builder()
                        .id("m2")
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content("Calling tool")
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolCalls(List.of(new AssistantMessage.ToolCall("tool-1", "function", "search", "{}")))
                                .build())
                        .seqNo(2L)
                        .build(),
                ChatMessageDTO.builder()
                        .id("m3")
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .role(ChatMessageDTO.RoleType.TOOL)
                        .content("tool output")
                        .seqNo(3L)
                        .build(),
                ChatMessageDTO.builder()
                        .id("m4")
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content("The order AB-1234 was approved.")
                        .seqNo(4L)
                        .build(),
                ChatMessageDTO.builder()
                        .id("m5")
                        .sessionId("session-1")
                        .turnId("turn-2")
                        .role(ChatMessageDTO.RoleType.USER)
                        .content("Thanks")
                        .seqNo(5L)
                        .build(),
                ChatMessageDTO.builder()
                        .id("m6")
                        .sessionId("session-1")
                        .turnId("turn-2")
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content("You are welcome.")
                        .seqNo(6L)
                        .build()
        ));

        List<AtomicConversationTurn> turns = turnBasedContextExtractor.extractPendingTurns("session-1", 6L);

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).turnId()).isEqualTo("turn-1");
        assertThat(turns.get(0).userMessages()).containsExactly("Order AB-1234 on 2026-03-28");
        assertThat(turns.get(0).assistantConclusion()).isEqualTo("The order AB-1234 was approved.");
        assertThat(turns.get(0).startSeqNo()).isEqualTo(1L);
        assertThat(turns.get(0).endSeqNo()).isEqualTo(4L);
        assertThat(turns.get(1).turnId()).isEqualTo("turn-2");
        assertThat(turns.get(1).assistantConclusion()).isEqualTo("You are welcome.");
        verify(summaryWatermarkService).loadPendingMessages("session-1", 6L);
    }
}
