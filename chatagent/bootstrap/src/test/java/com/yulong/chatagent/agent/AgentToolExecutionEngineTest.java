package com.yulong.chatagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolExecutionEngineTest {

    @Test
    void shouldExecuteRegisteredRuntimeToolCallbacks() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);
        when(chatMemory.get("session-1")).thenReturn(List.of(new UserMessage("what date is it")));

        ToolCallback toolCallback = MethodToolCallbackProvider.builder()
                .toolObjects(new TestDateTool())
                .build()
                .getToolCallbacks()[0];

        AgentToolExecutionEngine executionEngine = new AgentToolExecutionEngine(
                List.of(toolCallback),
                DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build(),
                "turn-1",
                messageBridge
        );

        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "getDate",
                                "{}"
                        )))
                        .build()
        )));

        boolean terminated = executionEngine.execute(chatMemory, "session-1", chatResponse);

        assertThat(terminated).isFalse();
        verify(chatMemory).clear("session-1");
        verify(chatMemory).add(eq("session-1"), any(List.class));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Message> messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messageBridge).persistAndPublish(eq("session-1"), eq("turn-1"), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) messageCaptor.getValue();
        assertThat(toolResponseMessage.getResponses()).singleElement().satisfies(response -> {
            assertThat(response.name()).isEqualTo("getDate");
            assertThat(response.responseData()).contains("2026-04-09");
        });
    }

    static final class TestDateTool {

        @org.springframework.ai.tool.annotation.Tool(name = "getDate", description = "Return the current date.")
        public String getDate() {
            return "2026-04-09";
        }
    }
}
