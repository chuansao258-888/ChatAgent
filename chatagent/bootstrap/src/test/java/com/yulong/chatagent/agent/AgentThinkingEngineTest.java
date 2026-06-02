package com.yulong.chatagent.agent;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentThinkingEngineTest {

    private static final String SESSION_ID = "session-1";
    private static final String TURN_ID = "turn-1";

    @Mock
    private LLMService llmService;

    @Mock
    private AgentMessageBridge messageBridge;

    @Mock
    private ChatMemory chatMemory;

    @Test
    void shouldStreamFinalAnswerDirectlyWhenNoToolsAreAvailable() {
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("hello")));
        when(messageBridge.streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), anyBoolean()))
                .thenReturn("direct final answer");

        AgentThinkingEngine engine = engineWithTools(List.of());

        ChatResponse response = engine.think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("direct final answer");
        assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();
        verify(messageBridge).streamFinalResponse(eq(SESSION_ID), eq(TURN_ID), any(Prompt.class), same(llmService), anyBoolean());
        verify(messageBridge, never()).streamDecisionResponse(
                any(), any(), any(Prompt.class), any(), any(), same(llmService));
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
    }

    @Test
    void shouldPersistAndPublishToolDecisionWhenModelReturnsToolCalls() {
        ToolCallback tool = mock(ToolCallback.class);
        List<AssistantMessage.ToolCall> toolCalls = List.of(new AssistantMessage.ToolCall(
                "tool-call-1",
                "function",
                "SessionFileSearchTool",
                "{\"query\":\"budget\"}"
        ));
        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getToolCalls()).thenReturn(toolCalls);
        BufferedStreamingResponse decision = new BufferedStreamingResponse(response(output), List.of());
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("search my files")));
        when(messageBridge.streamDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(tool)),
                same(llmService)
        )).thenReturn(decision);

        ChatResponse response = engineWithTools(List.of(tool)).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput()).isSameAs(output);
        verify(messageBridge).persistAndPublish(SESSION_ID, TURN_ID, output);
        verify(messageBridge, never()).streamFinalResponse(any(), any(), any(Prompt.class), same(llmService), anyBoolean());
    }

    @Test
    void shouldFinalizeLivePassthroughWhenToolsAreAvailableButModelReturnsPureText() {
        ToolCallback tool = mock(ToolCallback.class);
        AssistantMessage output = new AssistantMessage("pure text final answer");
        BufferedStreamingResponse decision = new BufferedStreamingResponse(response(output), List.of(
                new BufferedStreamingResponse.BufferedStreamEvent(
                        BufferedStreamingResponse.EventType.CONTENT,
                        "pure text final answer"
                )
        ));
        when(chatMemory.get(SESSION_ID)).thenReturn(List.of(new UserMessage("answer directly")));
        when(messageBridge.streamDecisionResponse(
                eq(SESSION_ID),
                eq(TURN_ID),
                any(Prompt.class),
                contains("Decision Module"),
                eq(List.of(tool)),
                same(llmService)
        )).thenReturn(decision);

        ChatResponse response = engineWithTools(List.of(tool)).think(chatMemory, SESSION_ID);

        assertThat(response.getResult().getOutput()).isSameAs(output);
        assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();
        verify(messageBridge, never()).persistAndPublish(any(), any(), any());
        verify(messageBridge, never()).streamFinalResponse(any(), any(), any(Prompt.class), same(llmService), anyBoolean());
    }

    private AgentThinkingEngine engineWithTools(List<ToolCallback> tools) {
        return new AgentThinkingEngine(
                TestPromptLoader.create(),
                llmService,
                DefaultToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build(),
                tools,
                "session file summary",
                "user profile summary",
                TURN_ID,
                messageBridge,
                4  // maxToolCallsPerStep
        );
    }

    private static ChatResponse response(AssistantMessage output) {
        return new ChatResponse(List.of(new Generation(output)));
    }
}
