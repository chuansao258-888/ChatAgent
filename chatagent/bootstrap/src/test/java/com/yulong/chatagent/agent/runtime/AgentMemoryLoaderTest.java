package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.support.dto.AgentDTO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentMemoryLoaderTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private AgentMemoryLoader agentMemoryLoader;

    @BeforeEach
    void setUp() {
        agentMemoryLoader = new AgentMemoryLoader(chatMessageRepository);
    }

    @Test
    void shouldKeepMostRecentTurnsWithinTokenBudget() {
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

        assertThat(memory).hasSize(4);
        assertThat(memory).extracting(Message::getText)
                .containsExactly("ccc", "ddd", "eee", "fff");
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

    private AgentDTO agentWithBudget(int tokenBudget) {
        return AgentDTO.builder()
                .chatOptions(AgentDTO.ChatOptions.builder()
                        .messageLength(10)
                        .tokenBudget(tokenBudget)
                        .build())
                .build();
    }

    private ChatMessageDTO message(String id, String turnId, ChatMessageDTO.RoleType role, String content) {
        return ChatMessageDTO.builder()
                .id(id)
                .sessionId("session-1")
                .turnId(turnId)
                .role(role)
                .content(content)
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
