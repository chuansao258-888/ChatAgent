package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.conversation.port.ChatSessionSummaryRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.support.dto.ChatSessionSummaryDTO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryLoaderTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatSessionSummaryRepository chatSessionSummaryRepository;

    private ToolResultCompactor toolResultCompactor;

    private AgentMemoryLoader agentMemoryLoader;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        toolResultCompactor = new ToolResultCompactor(200, 80, 80, meterProvider);
        agentMemoryLoader = new AgentMemoryLoader(chatMessageRepository, chatSessionSummaryRepository, toolResultCompactor, 8, 1);
    }

    @Test
    void shouldKeepRecentUserInputsWhenFullTurnsExceedTokenBudget() {
        AgentDTO agent = agentWithBudget(20);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "aaa"),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "bbb"),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER, "ccc"),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, "ddd"),
                message("m5", "turn-3", ChatMessageDTO.RoleType.USER, "eee"),
                message("m6", "turn-3", ChatMessageDTO.RoleType.ASSISTANT, "fff")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .containsExactly("aaa", "ccc", "ddd", "eee", "fff");
    }

    @Test
    void shouldKeepSingleOversizedLatestTurnEvenWhenItExceedsBudget() {
        AgentDTO agent = agentWithBudget(10);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "old"),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "old"),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER, "123456"),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, "abcdef")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).hasSize(2);
        assertThat(memory).extracting(Message::getText)
                .containsExactly("123456", "abcdef");
    }

    @Test
    void shouldSkipIncompleteAssistantToolCallSequence() {
        AgentDTO agent = agentWithBudget(100);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "Need status update"),
                assistantWithToolCalls(
                        "m2",
                        "turn-1",
                        "Checking now",
                        new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}"),
                        new AssistantMessage.ToolCall("call-2", "function", "notify", "{}")
                ),
                toolMessage("m3", "turn-1", new ToolResponseMessage.ToolResponse("call-1", "lookup", "{\"ok\":true}"))
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).hasSize(1);
        assertThat(memory.get(0).getText()).isEqualTo("Need status update");
    }

    @Test
    void shouldCompactOversizedToolResultInMemory() {
        AgentDTO agent = agentWithBudget(10000);
        String largeResponse = "X".repeat(500);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "Check order"),
                assistantWithToolCalls("m2", "turn-1", "Looking up",
                        new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")),
                toolMessage("m3", "turn-1", new ToolResponseMessage.ToolResponse("call-1", "lookup", largeResponse))
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        // Should have user, assistant, and compacted tool response
        assertThat(memory).hasSize(3);
        Message toolMessageResult = memory.get(2);
        assertThat(toolMessageResult).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResult = (ToolResponseMessage) toolMessageResult;
        // Content should be compacted via ToolResultCompactor
        assertThat(toolResult.getResponses().get(0).responseData())
                .startsWith("[Tool result compacted for context budget]");
        assertThat(toolResult.getResponses().get(0).responseData())
                .contains("Original chars: 500");
    }

    @Test
    void shouldPreserveToolCallIdAfterCompaction() {
        AgentDTO agent = agentWithBudget(10000);
        String largeResponse = "Y".repeat(500);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "Query"),
                assistantWithToolCalls("m2", "turn-1", "Working",
                        new AssistantMessage.ToolCall("call-42", "function", "search", "{}")),
                toolMessage("m3", "turn-1", new ToolResponseMessage.ToolResponse("call-42", "search", largeResponse))
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        ToolResponseMessage toolMsg = (ToolResponseMessage) memory.get(2);
        assertThat(toolMsg.getResponses()).hasSize(1);
        assertThat(toolMsg.getResponses().get(0).id()).isEqualTo("call-42");
        assertThat(toolMsg.getResponses().get(0).name()).isEqualTo("search");
    }

    @Test
    void shouldNotCompactSmallToolResult() {
        AgentDTO agent = agentWithBudget(10000);
        String smallResponse = "Result: OK";
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "Check"),
                assistantWithToolCalls("m2", "turn-1", "Checking",
                        new AssistantMessage.ToolCall("call-1", "function", "check", "{}")),
                toolMessage("m3", "turn-1", new ToolResponseMessage.ToolResponse("call-1", "check", smallResponse))
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        ToolResponseMessage toolMsg = (ToolResponseMessage) memory.get(2);
        // Small response should pass through unchanged
        assertThat(toolMsg.getResponses().get(0).responseData()).isEqualTo(smallResponse);
    }

    @Test
    void shouldNotCompactPersistedToolResultContent() {
        // Verify the original ChatMessageDTO content is not modified:
        // compaction only affects the derived Spring AI message list.
        AgentDTO agent = agentWithBudget(10000);
        String largeResponse = "Z".repeat(500);
        ChatMessageDTO toolMsg = toolMessage("m3", "turn-1",
                new ToolResponseMessage.ToolResponse("call-1", "search", largeResponse));
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "Query"),
                assistantWithToolCalls("m2", "turn-1", "Working",
                        new AssistantMessage.ToolCall("call-1", "function", "search", "{}")),
                toolMsg
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        // The Spring AI message is compacted
        ToolResponseMessage compactedMsg = (ToolResponseMessage) memory.get(2);
        assertThat(compactedMsg.getResponses().get(0).responseData()).startsWith("[Tool result compacted");

        // The original DTO content is unchanged — no DB mutation
        assertThat(toolMsg.getMetadata().getToolResponse().responseData()).isEqualTo(largeResponse);
    }

    @Test
    void shouldCompactToolResultWhenTurnExceedsTokenBudget() {
        // Budget-pressure path: tool response below maxChars (200) but turn exceeds budget.
        // Budget=160 -> effective=128. Raw turn-1 plus turn-2 exceeds that.
        // Budget compaction shrinks the tool response enough for both turns to fit.
        String mediumResponse = "X".repeat(150); // below maxChars=200
        AgentDTO agent = agentWithBudget(160);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "Search"),
                assistantWithToolCalls("m2", "turn-1", "Looking",
                        new AssistantMessage.ToolCall("call-1", "function", "search", "{}")),
                toolMessage("m3", "turn-1",
                        new ToolResponseMessage.ToolResponse("call-1", "search", mediumResponse)),
                message("m4", "turn-2", ChatMessageDTO.RoleType.USER, "Hi"),
                message("m5", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, "ok")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        // Both turns should be included (turn-1 after budget compaction)
        assertThat(memory).extracting(Message::getText).contains("Hi", "ok", "Search", "Looking");

        // Turn-1's tool response must be budget-compacted (below maxChars but triggered by budget)
        ToolResponseMessage toolResult = memory.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst().orElse(null);
        assertThat(toolResult).isNotNull();
        assertThat(toolResult.getResponses().get(0).responseData())
                .startsWith("[Tool result compacted for context budget]");
        // Tool call ID must be preserved
        assertThat(toolResult.getResponses().get(0).id()).isEqualTo("call-1");
    }

    @Test
    void shouldCompactOlderToolResultWhenAddingTurnWouldExceedTotalBudget() {
        // The older turn fits by itself, but adding it to newer turns exceeds the total L1 budget.
        // The loader should compact only the older tool result and keep the user's durable fact.
        String mediumResponse = "R".repeat(150); // below char compaction maxChars=200
        AgentDTO agent = agentWithBudget(350); // effective budget = 280
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER,
                        "For this incident only, the identifier is INCIDENT-TEST."),
                assistantWithToolCalls("m2", "turn-1", "Looking",
                        new AssistantMessage.ToolCall("call-1", "function", "search", "{}")),
                toolMessage("m3", "turn-1",
                        new ToolResponseMessage.ToolResponse("call-1", "search", mediumResponse)),
                message("m4", "turn-2", ChatMessageDTO.RoleType.USER,
                        "The project codename is NORTHSTAR-TEST."),
                message("m5", "turn-2", ChatMessageDTO.RoleType.ASSISTANT,
                        "Noted. The project codename is NORTHSTAR-TEST."),
                message("m6", "turn-3", ChatMessageDTO.RoleType.USER,
                        "What was the incident identifier?")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .contains(
                        "For this incident only, the identifier is INCIDENT-TEST.",
                        "The project codename is NORTHSTAR-TEST.",
                        "What was the incident identifier?");

        ToolResponseMessage toolResult = memory.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst().orElse(null);
        assertThat(toolResult).isNotNull();
        assertThat(toolResult.getResponses().get(0).responseData())
                .startsWith("[Tool result compacted for context budget]");
    }

    @Test
    void shouldKeepOnlyConfiguredRawTurnTail() {
        agentMemoryLoader = new AgentMemoryLoader(chatMessageRepository, chatSessionSummaryRepository, toolResultCompactor, 3, 1);
        AgentDTO agent = agentWithBudget(1000);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "turn one"),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "answer one"),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER, "turn two"),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, "answer two"),
                message("m5", "turn-3", ChatMessageDTO.RoleType.USER, "turn three"),
                message("m6", "turn-3", ChatMessageDTO.RoleType.ASSISTANT, "answer three"),
                message("m7", "turn-4", ChatMessageDTO.RoleType.USER, "turn four"),
                message("m8", "turn-4", ChatMessageDTO.RoleType.ASSISTANT, "answer four")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .containsExactly("turn two", "answer two", "turn three", "answer three", "turn four", "answer four");
    }

    @Test
    void shouldExcludeTurnsAlreadyCoveredByL2Watermark() {
        AgentDTO agent = agentWithBudget(1000);
        when(chatSessionSummaryRepository.findBySessionId("session-1")).thenReturn(ChatSessionSummaryDTO.builder()
                .sessionId("session-1")
                .summarizedUntilSeqNo(4L)
                .build());
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "covered question", 1L),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "covered answer", 2L),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER, "also covered", 3L),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, "also covered answer", 4L),
                message("m5", "turn-3", ChatMessageDTO.RoleType.USER, "raw question", 5L),
                message("m6", "turn-3", ChatMessageDTO.RoleType.ASSISTANT, "raw answer", 6L)
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .containsExactly("raw question", "raw answer");
    }

    @Test
    void shouldKeepOpenCurrentTurnInAdditionToConfiguredCompletedTail() {
        agentMemoryLoader = new AgentMemoryLoader(chatMessageRepository, chatSessionSummaryRepository, toolResultCompactor, 2, 1);
        AgentDTO agent = agentWithBudget(1000);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "turn one"),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "answer one"),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER, "turn two"),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, "answer two"),
                message("m5", "turn-3", ChatMessageDTO.RoleType.USER, "turn three"),
                message("m6", "turn-3", ChatMessageDTO.RoleType.ASSISTANT, "answer three"),
                message("m7", "turn-4", ChatMessageDTO.RoleType.USER, "current question")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .containsExactly("turn two", "answer two", "turn three", "answer three", "current question");
    }

    @Test
    void shouldPreserveRecentUserCorrectionsWhenLongAnswersExhaustBudget() {
        AgentDTO agent = agentWithBudget(400);
        String longAnswer = "Detailed unrelated guidance. ".repeat(20);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER,
                        "The rehearsal starts in room MAPLE and Priya owns it."),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, longAnswer),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER,
                        "Facilities moved the rehearsal from MAPLE to ORBIT."),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, longAnswer),
                message("m5", "turn-3", ChatMessageDTO.RoleType.USER,
                        "Priya handed ownership to Elena."),
                message("m6", "turn-3", ChatMessageDTO.RoleType.ASSISTANT, longAnswer),
                message("m7", "turn-4", ChatMessageDTO.RoleType.USER,
                        "How should I reheat roasted vegetables?"),
                message("m8", "turn-4", ChatMessageDTO.RoleType.ASSISTANT, longAnswer),
                message("m9", "turn-5", ChatMessageDTO.RoleType.USER,
                        "Who owns the rehearsal now, and where is it being held?")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).filteredOn(UserMessage.class::isInstance)
                .extracting(Message::getText)
                .contains(
                        "Facilities moved the rehearsal from MAPLE to ORBIT.",
                        "Priya handed ownership to Elena.",
                        "Who owns the rehearsal now, and where is it being held?");
        assertThat(memory).extracting(Message::getText).doesNotContain(longAnswer);
    }

    @Test
    void shouldUseGlobalTokenBudgetAsFloorForLegacyAgentConfiguration() {
        agentMemoryLoader = new AgentMemoryLoader(chatMessageRepository, chatSessionSummaryRepository, toolResultCompactor, 8, 500);
        AgentDTO agent = agentWithBudget(100);
        String firstAnswer = "first answer ".repeat(8);
        String secondAnswer = "second answer ".repeat(8);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "first user question"),
                message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, firstAnswer),
                message("m3", "turn-2", ChatMessageDTO.RoleType.USER, "second user question"),
                message("m4", "turn-2", ChatMessageDTO.RoleType.ASSISTANT, secondAnswer)
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .containsExactly("first user question", firstAnswer, "second user question", secondAnswer);
    }

    @Test
    void shouldReturnEmptyWhenLegacyMessagesHaveNoTurnId() {
        AgentDTO agent = agentWithBudget(1000);
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", null, ChatMessageDTO.RoleType.USER, "legacy question"),
                message("m2", null, ChatMessageDTO.RoleType.ASSISTANT, "legacy answer")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).isEmpty();
    }

    @Test
    void shouldExcludeMessagesMarkedInternal() {
        AgentDTO agent = agentWithBudget(1000);
        ChatMessageDTO internal = message("m2", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "hidden decision");
        internal.setMetadata(ChatMessageDTO.MetaData.builder().internal(true).build());
        when(chatMessageRepository.findRecentBySessionId("session-1", 100)).thenReturn(List.of(
                message("m1", "turn-1", ChatMessageDTO.RoleType.USER, "visible question"),
                internal,
                message("m3", "turn-1", ChatMessageDTO.RoleType.ASSISTANT, "visible answer")
        ));

        List<Message> memory = agentMemoryLoader.load("session-1", agent);

        assertThat(memory).extracting(Message::getText)
                .containsExactly("visible question", "visible answer");
    }

    private AgentDTO agentWithBudget(int tokenBudget) {
        return AgentDTO.builder()
                .chatOptions(AgentDTO.ChatOptions.builder()
                        .messageLength(10)
                        .tokenBudget(tokenBudget)
                        .build())
                .build();
    }

    private ChatMessageDTO message(String id, String turnId, ChatMessageDTO.RoleType role, String content) {
        return message(id, turnId, role, content, null);
    }

    private ChatMessageDTO message(String id, String turnId, ChatMessageDTO.RoleType role, String content, Long seqNo) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .turnId(turnId)
                .role(role)
                .content(content)
                .seqNo(seqNo)
                .build();
    }

    private ChatMessageDTO assistantWithToolCalls(String id,
                                                  String turnId,
                                                  String content,
                                                  AssistantMessage.ToolCall... toolCalls) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .turnId(turnId)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(toolCalls))
                        .build())
                .build();
    }

    private ChatMessageDTO toolMessage(String id, String turnId, ToolResponseMessage.ToolResponse toolResponse) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .turnId(turnId)
                .role(ChatMessageDTO.RoleType.TOOL)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(toolResponse)
                        .build())
                .build();
    }
}
