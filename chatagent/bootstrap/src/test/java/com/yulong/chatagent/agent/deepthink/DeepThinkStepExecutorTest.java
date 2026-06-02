package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DeepThinkStepExecutor} — bounded ReAct sub-loop per plan step.
 */
@ExtendWith(MockitoExtension.class)
class DeepThinkStepExecutorTest {

    @Mock private AgentMessageBridge messageBridge;
    @Mock private LLMService llmService;
    @Mock private PromptLoader promptLoader;

    private DeepThinkStepExecutor executor;
    private List<ToolCallback> tools;

    @BeforeEach
    void setUp() {
        tools = List.of(mockToolCallback("knowledgeQuery"));
        when(promptLoader.render(anyString(), any())).thenReturn("rendered step prompt");
        executor = new DeepThinkStepExecutor(messageBridge, llmService, tools, false, promptLoader);
    }

    @Test
    void executeStep_noToolCall_returnsConclusion() {
        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("搜索").objective("搜索相关信息")
                .doneCriteria(List.of("获得结果"))
                .expectedEvidence(List.of("证据"))
                .build();

        // LLM returns text conclusion (no tool calls)
        AssistantMessage assistant = AssistantMessage.builder()
                .content("找到了相关信息：版本号为 1.0.0")
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistant)));
        BufferedStreamingResponse buffered = new BufferedStreamingResponse(chatResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                eq(tools), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(buffered);

        DeepThinkNotebook notebook = new DeepThinkNotebook();
        String conclusion = executor.executeStep(
                "session-1", "turn-1", step, 3, notebook, "test goal", "暂无观察", 0, 0);

        assertThat(conclusion).contains("找到了相关信息");
        assertThat(notebook.getTotalLlmCalls()).isEqualTo(1);
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                any(), anyString());
    }

    @Test
    void executeStep_toolCallThenConclusion_returnsResult() {
        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("搜索").objective("搜索相关信息")
                .doneCriteria(List.of("获得结果"))
                .build();

        // First call: tool call
        AssistantMessage toolCallMsg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "knowledgeQuery", "{\"query\":\"test\"}")))
                .build();
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(toolCallMsg)));
        BufferedStreamingResponse toolCallBuffered = new BufferedStreamingResponse(toolCallResponse, List.of());

        // Second call: conclusion
        AssistantMessage conclusionMsg = AssistantMessage.builder()
                .content("搜索结果：找到了 3 条相关信息")
                .build();
        ChatResponse conclusionResponse = new ChatResponse(List.of(new Generation(conclusionMsg)));
        BufferedStreamingResponse conclusionBuffered = new BufferedStreamingResponse(conclusionResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                eq(tools), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(toolCallBuffered).thenReturn(conclusionBuffered);

        DeepThinkNotebook notebook = new DeepThinkNotebook();
        String conclusion = executor.executeStep(
                "session-1", "turn-1", step, 3, notebook, "test goal", "暂无观察", 0, 0);

        assertThat(conclusion).contains("搜索结果");
        assertThat(notebook.getTotalLlmCalls()).isEqualTo(2);
        assertThat(notebook.getTotalToolCalls()).isEqualTo(1);
        verify(messageBridge).persistInternalToolResponses(
                eq("session-1"), eq("turn-1"), any(ToolResponseMessage.class),
                eq("EXECUTE"), eq("S1"));
    }

    @Test
    void executeStep_toolCallsExceedBudget_truncatesToRemaining() {
        // 需要两个不同的工具名，验证截断只执行第一个
        List<ToolCallback> multiTools = List.of(
                mockToolCallback("toolA"),
                mockToolCallback("toolB"),
                mockToolCallback("toolC"));
        DeepThinkStepExecutor multiExecutor = new DeepThinkStepExecutor(
                messageBridge, llmService, multiTools, false, promptLoader);

        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("多工具").objective("调用多个工具")
                .build();

        // 模型一次返回 3 个 tool calls
        AssistantMessage multiToolMsg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "toolA", "{}"),
                        new AssistantMessage.ToolCall("call-2", "function", "toolB", "{}"),
                        new AssistantMessage.ToolCall("call-3", "function", "toolC", "{}")))
                .build();
        ChatResponse multiToolResponse = new ChatResponse(List.of(new Generation(multiToolMsg)));
        BufferedStreamingResponse multiToolBuffered = new BufferedStreamingResponse(multiToolResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                eq(multiTools), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(multiToolBuffered);

        DeepThinkNotebook notebook = new DeepThinkNotebook();
        // 预算: maxTotalToolCalls=1 → 剩 1 次，模型返回 3 个 tool calls → 只执行 1 个
        String conclusion = multiExecutor.executeStep(
                "session-1", "turn-1", step, 3, notebook, "test goal", "暂无观察", 1, 0);

        // 应该是"部分完成"——因为截断后没有第二次迭代（预算耗尽直接 break）
        assertThat(conclusion).isEqualTo("部分完成");
        // 只执行了 1 个工具调用，不是 3 个
        assertThat(notebook.getTotalToolCalls()).isEqualTo(1);
        // toolCalls 记录只有截断后的那一个
        assertThat(notebook.getToolsUsed()).containsExactly("toolA");
    }

    @Test
    void executeStep_toolCallFeedsHistoryIntoNextIteration() {
        DeepThinkPlanStep step = DeepThinkPlanStep.builder()
                .id("S1").title("搜索").objective("搜索相关信息")
                .doneCriteria(List.of("获得结果"))
                .build();

        // First call: tool call
        AssistantMessage toolCallMsg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "knowledgeQuery", "{\"query\":\"test\"}")))
                .build();
        ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(toolCallMsg)));
        BufferedStreamingResponse toolCallBuffered = new BufferedStreamingResponse(toolCallResponse, List.of());

        // Second call: conclusion
        AssistantMessage conclusionMsg = AssistantMessage.builder()
                .content("最终结论")
                .build();
        ChatResponse conclusionResponse = new ChatResponse(List.of(new Generation(conclusionMsg)));
        BufferedStreamingResponse conclusionBuffered = new BufferedStreamingResponse(conclusionResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                eq(tools), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(toolCallBuffered).thenReturn(conclusionBuffered);

        DeepThinkNotebook notebook = new DeepThinkNotebook();
        executor.executeStep("session-1", "turn-1", step, 3, notebook,
                "test goal", "暂无观察", 0, 0);

        // Capture all Prompts sent to collectDecisionResponse
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(messageBridge, times(2)).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), promptCaptor.capture(), anyString(),
                eq(tools), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1"));

        List<Prompt> allPrompts = promptCaptor.getAllValues();
        Prompt secondPrompt = allPrompts.get(1);
        List<org.springframework.ai.chat.messages.Message> history = secondPrompt.getInstructions();

        // Second iteration prompt must contain the assistant tool_call from first iteration
        boolean hasAssistantToolCall = history.stream()
                .anyMatch(m -> m instanceof AssistantMessage am
                        && am.getToolCalls() != null && !am.getToolCalls().isEmpty());
        assertThat(hasAssistantToolCall)
                .as("Second iteration prompt should contain assistant tool_call from first iteration")
                .isTrue();

        // Second iteration prompt must contain the tool response
        boolean hasToolResponse = history.stream()
                .anyMatch(m -> m instanceof ToolResponseMessage);
        assertThat(hasToolResponse)
                .as("Second iteration prompt should contain ToolResponseMessage from first iteration")
                .isTrue();

        // Verify history has at least: SystemMessage + UserMessage + AssistantMessage(toolCall) + ToolResponseMessage + UserMessage(continue)
        assertThat(history.size()).isGreaterThanOrEqualTo(5);
    }

    private ToolCallback mockToolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        lenient().when(callback.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name(name)
                        .description("test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build()
        );
        lenient().when(callback.getToolMetadata()).thenReturn(
                ToolMetadata.builder().returnDirect(true).build()
        );
        lenient().when(callback.call(any())).thenReturn("tool result");
        return callback;
    }
}
