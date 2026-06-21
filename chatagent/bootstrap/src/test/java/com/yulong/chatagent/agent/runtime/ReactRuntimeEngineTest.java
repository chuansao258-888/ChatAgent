package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReactRuntimeEngine 单元测试。
 * <p>
 * 覆盖：直接回答、工具调用、多步工具调用、max-step 强制最终综合、
 * 非法状态拒绝、异常包装。
 */
class ReactRuntimeEngineTest {

    private PromptLoader promptLoader;
    private LLMService llmService;
    private AgentMessageBridge messageBridge;
    private ChatMemory chatMemory;
    private ChatOptions chatOptions;

    @BeforeEach
    void setUp() {
        promptLoader = mock(PromptLoader.class);
        llmService = mock(LLMService.class);
        messageBridge = mock(AgentMessageBridge.class);
        chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();
        chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 默认 prompt 加载行为
        when(promptLoader.load(any())).thenReturn("fallback prompt");
        when(promptLoader.render(any(), any())).thenReturn("rendered prompt");
    }

    // ========== 直接回答（无工具调用）==========

    @Test
    void directAnswer_shouldReturnSuccess() {
        // 模型第一次就给出最终答案（无 tool calls）
        ChatResponse directAnswer = new ChatResponse(List.of(new Generation(
                new AssistantMessage("这是最终答案")
        )));
        BufferedStreamingResponse bufferedResponse = new BufferedStreamingResponse(directAnswer, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(bufferedResponse);
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(), eq(llmService), eq(false)
        )).thenReturn("这是最终答案");

        AgentRunContext context = buildContext(List.of(mockToolCallback("tool1")));
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        verify(messageBridge).streamFinalResponse(eq("session-1"), eq("turn-1"), any(), eq(llmService), eq(false));
    }

    // ========== 无可用工具 ==========

    @Test
    void noTools_shouldStreamFinalAnswerDirectly() {
        // 没有工具时，thinkingEngine 走 streamFinalAnswer 分支
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(), eq(llmService), anyBoolean()
        )).thenReturn("最终答案");

        AgentRunContext context = buildContext(List.of());
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        verify(messageBridge).streamFinalResponse(eq("session-1"), eq("turn-1"), any(), eq(llmService), anyBoolean());
    }

    // ========== Max-step 强制最终综合 ==========

    @Test
    void maxStepsReached_shouldForceFinalSynthesis() {
        // 用 maxSteps=2 的策略，模型每步都返回 tool call，触发 max-step
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "tool1", "{}"
                        )))
                        .build()
        )));
        BufferedStreamingResponse bufferedResponse = new BufferedStreamingResponse(toolCallResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                any(), any(), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(bufferedResponse);
        when(messageBridge.streamFinalResponse(
                any(), any(), any(), eq(llmService), anyBoolean()
        )).thenReturn("强制最终答案");

        AgentRunContext context = buildContextWithMaxSteps(2, List.of(mockToolCallback("tool1")));
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        // 应该调用 streamFinalResponse 进行强制最终综合
        verify(messageBridge).streamFinalResponse(eq("session-1"), eq("turn-1"), any(), eq(llmService), anyBoolean());
    }

    // ========== 异常包装 ==========

    @Test
    void exceptionDuringRun_shouldWrapInAgentRunException() {
        // 模型调用抛异常
        when(messageBridge.collectDecisionResponse(
                any(), any(), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenThrow(new RuntimeException("model failed"));

        AgentRunContext context = buildContext(List.of(mockToolCallback("tool1")));
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        assertThatThrownBy(() -> engine.run(context))
                .isInstanceOf(AgentRunException.class)
                .hasRootCauseMessage("model failed");

        // 内部 AgentRunResult 应该是失败
        // AgentRunException 携带 failure result
    }

    // ========== 重复运行拒绝 ==========

    @Test
    void runTwice_shouldThrowIllegalState() {
        ChatResponse directAnswer = new ChatResponse(List.of(new Generation(
                new AssistantMessage("最终答案")
        )));
        BufferedStreamingResponse bufferedResponse = new BufferedStreamingResponse(directAnswer, List.of());

        when(messageBridge.collectDecisionResponse(
                any(), any(), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(bufferedResponse);
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(), eq(llmService), eq(false)
        )).thenReturn("最终答案");

        AgentRunContext context = buildContext(List.of(mockToolCallback("tool1")));
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        // 第一次运行成功
        engine.run(context);

        // 第二次运行应该抛异常
        assertThatThrownBy(() -> engine.run(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not idle");
    }

    // ========== maxToolCallsPerStep 截断 ==========

    @Test
    void tooManyToolCalls_shouldTruncateToLimit() {
        // 模型一步返回 5 个 tool calls，maxToolCallsPerStep=2 应截断为 2
        ChatResponse multiToolResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("call-1", "function", "tool1", "{}"),
                                new AssistantMessage.ToolCall("call-2", "function", "tool1", "{}"),
                                new AssistantMessage.ToolCall("call-3", "function", "tool1", "{}"),
                                new AssistantMessage.ToolCall("call-4", "function", "tool1", "{}"),
                                new AssistantMessage.ToolCall("call-5", "function", "tool1", "{}")
                        ))
                        .build()
        )));
        BufferedStreamingResponse bufferedResponse = new BufferedStreamingResponse(multiToolResponse, List.of());

        // 第二步给直接回答
        ChatResponse directAnswer = new ChatResponse(List.of(new Generation(
                new AssistantMessage("最终答案")
        )));
        BufferedStreamingResponse directBuffered = new BufferedStreamingResponse(directAnswer, List.of());

        when(messageBridge.collectDecisionResponse(
                any(), any(), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(bufferedResponse).thenReturn(directBuffered);
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(), eq(llmService), eq(false)
        )).thenReturn("最终答案");

        // maxToolCallsPerStep = 2
        AgentRunContext context = buildContextWithPolicy(20, 2, List.of(mockToolCallback("tool1")));
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        // 应该执行了 2 步：第一步 5 个 tool call 被截断为 2，第二步直接回答
        verify(messageBridge, times(2)).collectDecisionResponse(
                any(), any(), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        );
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageBridge, times(2)).persistAndPublish(
                eq("session-1"), eq("turn-1"), messageCaptor.capture());

        AssistantMessage persistedAssistant = messageCaptor.getAllValues()
                .stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(persistedAssistant.getToolCalls())
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactly("call-1", "call-2");
    }

    // ========== forceFinalSynthesis 失败不静默 ==========

    @Test
    void forceFinalSynthesisFailure_shouldThrowAgentRunException() {
        // 模型每步都返回 tool call，触发 max-step
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "tool1", "{}"
                        )))
                        .build()
        )));
        BufferedStreamingResponse bufferedResponse = new BufferedStreamingResponse(toolCallResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                any(), any(), any(), any(), any(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(bufferedResponse);
        // forceFinalSynthesis 调用 streamFinalResponse 时抛异常
        when(messageBridge.streamFinalResponse(
                any(), any(), any(), eq(llmService), anyBoolean()
        )).thenThrow(new RuntimeException("stream failed"));

        AgentRunContext context = buildContextWithMaxSteps(2, List.of(mockToolCallback("tool1")));
        ReactRuntimeEngine engine = new ReactRuntimeEngine(context);

        assertThatThrownBy(() -> engine.run(context))
                .isInstanceOf(AgentRunException.class)
                .hasRootCauseMessage("stream failed");
    }

    // ========== 辅助方法 ==========

    private AgentRunContext buildContext(List<ToolCallback> tools) {
        return buildContextWithMaxSteps(20, tools);
    }

    private AgentRunContext buildContextWithMaxSteps(int maxSteps, List<ToolCallback> tools) {
        return buildContextWithPolicy(maxSteps, 4, tools);
    }

    private AgentRunContext buildContextWithPolicy(int maxSteps, int maxToolCallsPerStep, List<ToolCallback> tools) {
        chatMemory.add("session-1", new SystemMessage("system prompt"));
        chatMemory.add("session-1", new UserMessage("user question"));

        AgentRunPolicy policy = new AgentRunPolicy(maxSteps, maxToolCallsPerStep);

        return new AgentRunContext(
                "agent-1",
                "test-agent",
                "session-1",
                "turn-1",
                "system prompt",
                promptLoader,
                llmService,
                chatMemory,
                chatOptions,
                tools,
                "file summary",
                "relevant long-term memory",
                messageBridge,
                policy,
                AgentExecutionMode.REACT
        );
    }

    private ToolCallback mockToolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name)
                        .description("test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build()
        );
        when(callback.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(true).build()
        );
        when(callback.call(any())).thenReturn("tool result");
        return callback;
    }
}
