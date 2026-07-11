package com.yulong.chatagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void shouldPropagateToolContextIntoRuntimeCallbackExecution() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        AgentMessageBridge messageBridge = mock(AgentMessageBridge.class);
        when(chatMemory.get("session-1")).thenReturn(List.of(new UserMessage("check status")));

        AtomicReference<ToolContext> seenContext = new AtomicReference<>();
        ToolCallback toolCallback = new CapturingToolCallback(seenContext);

        AgentToolExecutionEngine executionEngine = new AgentToolExecutionEngine(
                List.of(toolCallback),
                DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .toolContext(Map.of(
                                "userId", "user-1",
                                "sessionId", "session-1",
                                "turnId", "turn-1"
                        ))
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
                                "mcp_google_search",
                                "{\"query\":\"status\"}"
                        )))
                        .build()
        )));

        executionEngine.execute(chatMemory, "session-1", chatResponse);

        assertThat(seenContext.get()).isNotNull();
        assertThat(seenContext.get().getContext())
                .containsEntry("userId", "user-1")
                .containsEntry("sessionId", "session-1")
                .containsEntry("turnId", "turn-1");
    }

    @Test
    void shouldPropagateTheSameToolContextThroughDirectDeepThinkExecution() {
        AtomicReference<ToolContext> seenContext = new AtomicReference<>();
        ToolCallback callback = new CapturingToolCallback(seenContext);
        AgentToolExecutionEngine executionEngine = new AgentToolExecutionEngine(
                List.of(callback),
                DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .toolContext(Map.of(
                                "userId", "user-1",
                                "sessionId", "session-1",
                                "turnId", "turn-1"))
                        .build(),
                "turn-1",
                mock(AgentMessageBridge.class));
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "mcp_google_search", "{\"query\":\"status\"}")))
                .build();

        ToolResponseMessage response = executionEngine.executeToolCallsDirect(assistant);

        assertThat(response.getResponses()).singleElement()
                .satisfies(item -> assertThat(item.name()).isEqualTo("mcp_google_search"));
        assertThat(seenContext.get().getContext())
                .containsEntry("userId", "user-1")
                .containsEntry("sessionId", "session-1")
                .containsEntry("turnId", "turn-1");
    }

    @Test
    void shouldMapDirectToolFailureToStableSharedException() {
        ToolCallback failing = new CapturingToolCallback(new AtomicReference<>()) {
            @Override
            public String call(String toolInput, ToolContext toolContext) {
                throw new IllegalStateException("provider detail");
            }
        };
        AgentToolExecutionEngine executionEngine = new AgentToolExecutionEngine(
                List.of(failing),
                DefaultToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build(),
                "turn-1",
                mock(AgentMessageBridge.class));
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "mcp_google_search", "{\"query\":\"status\"}")))
                .build();

        assertThatThrownBy(() -> executionEngine.executeToolCallsDirect(assistant))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("Tool execution failed");
    }

    static final class TestDateTool {

        @org.springframework.ai.tool.annotation.Tool(name = "getDate", description = "Return the current date.")
        public String getDate() {
            return "2026-04-09";
        }
    }

    private static class CapturingToolCallback implements ToolCallback {

        private final AtomicReference<ToolContext> seenContext;
        private final ToolDefinition toolDefinition = ToolDefinition.builder()
                .name("mcp_google_search")
                .description("Search through MCP")
                .inputSchema("""
                        {"type":"object","properties":{"query":{"type":"string"}}}
                        """)
                .build();

        private CapturingToolCallback(AtomicReference<ToolContext> seenContext) {
            this.seenContext = seenContext;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            seenContext.set(toolContext);
            return "{\"status\":\"ok\"}";
        }
    }
}
