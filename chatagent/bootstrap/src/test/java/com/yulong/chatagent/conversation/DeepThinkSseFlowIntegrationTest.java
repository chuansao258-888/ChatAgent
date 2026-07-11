package com.yulong.chatagent.conversation;

import com.yulong.chatagent.agent.*;
import com.yulong.chatagent.agent.deepthink.DeepThinkRuntimeEngine;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentRunContext;
import com.yulong.chatagent.agent.runtime.AgentRunPolicy;
import com.yulong.chatagent.agent.runtime.ReactRuntimeEngine;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.support.dto.AgentTraceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests verifying SSE event sequence for DeepThink and ReAct modes.
 * <p>
 * Phase 6 acceptance: DeepThink emits AI_PLANNING → AI_EXECUTING → AI_THINKING status
 * events in order, attaches sanitized trace metadata to the final answer, and ReAct
 * mode never emits planning/executing status events.
 */
@ExtendWith(MockitoExtension.class)
class DeepThinkSseFlowIntegrationTest {

    @Mock private AgentMessageBridge messageBridge;
    @Mock private LLMService llmService;
    @Mock private PromptLoader promptLoader;

    private ChatMemory chatMemory;
    private ChatOptions chatOptions;
    private List<ToolCallback> tools;

    @BeforeEach
    void setUp() {
        chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();
        chatMemory.add("session-1", new SystemMessage("system"));
        chatMemory.add("session-1", new UserMessage("复杂问题"));
        chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false).build();
        tools = List.of(mockToolCallback("knowledgeQuery"));

        lenient().when(promptLoader.load(anyString())).thenReturn("fallback prompt");
        lenient().when(promptLoader.render(anyString(), any())).thenReturn("rendered prompt");
    }

    @Test
    void deepThinkSseFlow_emitsCorrectStatusSequenceAndAttachesTrace() {
        // --- Plan stage ---
        String planJson = """
                {"goal":"回答复杂问题","complexity":"HIGH","steps":[
                  {"id":"S1","title":"收集信息","objective":"搜索相关资料","doneCriteria":["获得结果"]},
                  {"id":"S2","title":"分析对比","objective":"对比多个来源","doneCriteria":["完成对比"]}
                ]}
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(buffered(planJson));

        // --- Step S1 execution ---
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(buffered("S1 结论：找到了相关资料"));

        // --- Step S2 execution ---
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S2")
        )).thenReturn(buffered("S2 结论：完成了来源对比"));

        // --- Reflection ---
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("REFLECT"), isNull()
        )).thenReturn(buffered("""
                {"status":"READY_TO_VERIFY","covered":["S1","S2"],"missing":[],"contradictions":[],"revisedSteps":[]}
                """));

        // --- Verification ---
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("VERIFY"), isNull()
        )).thenReturn(buffered("""
                {"passed":true,"issues":[],"requiredFollowUpActions":[],"caveat":""}
                """));

        // --- Final synthesis ---
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false)
        )).thenReturn("综合回答");

        // --- Run ---
        // 8-arg constructor: maxReflectionRounds=2, maxVerificationRounds=1
        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 30, 2, 1);
        AgentRunContext context = buildContext(policy, AgentExecutionMode.DEEPTHINK);
        AgentRunResult result = new DeepThinkRuntimeEngine(context).run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);

        // --- Capture all publishStatusEvent calls for exact sequence verification ---
        ArgumentCaptor<SseMessage.Type> typeCaptor = ArgumentCaptor.forClass(SseMessage.Type.class);
        verify(messageBridge, times(5)).publishStatusEvent(
                eq("session-1"), eq("turn-1"), typeCaptor.capture(), anyString());

        List<SseMessage.Type> statusSequence = typeCaptor.getAllValues();
        assertThat(statusSequence).containsExactly(
                SseMessage.Type.AI_PLANNING,      // planner
                SseMessage.Type.AI_EXECUTING,     // S1
                SseMessage.Type.AI_EXECUTING,     // S2
                SseMessage.Type.AI_THINKING,      // reflection
                SseMessage.Type.AI_THINKING       // verification
        );

        // --- Verify InOrder: status events -> final answer -> trace attach ---
        InOrder inOrder = inOrder(messageBridge);

        inOrder.verify(messageBridge, times(5)).publishStatusEvent(
                eq("session-1"), eq("turn-1"), any(SseMessage.Type.class), anyString());

        inOrder.verify(messageBridge).streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false));

        inOrder.verify(messageBridge).attachTraceMetadata(
                eq("session-1"), eq("turn-1"), any(AgentTraceMetadata.class));

        // --- Verify trace content ---
        ArgumentCaptor<AgentTraceMetadata> traceCaptor = ArgumentCaptor.forClass(AgentTraceMetadata.class);
        verify(messageBridge).attachTraceMetadata(eq("session-1"), eq("turn-1"), traceCaptor.capture());

        AgentTraceMetadata trace = traceCaptor.getValue();
        assertThat(trace.getMode()).isEqualTo("DEEPTHINK");
        assertThat(trace.getPlanning()).isNotNull();
        assertThat(trace.getPlanning().getStepCount()).isEqualTo(2);
        assertThat(trace.getPlanning().getSteps()).hasSize(2);
        assertThat(trace.getExecution()).isNotNull();
        assertThat(trace.getExecution().getTotalToolCalls()).isGreaterThanOrEqualTo(0);
        assertThat(trace.getVerification()).isNotNull();
        assertThat(trace.getVerification().isPassed()).isTrue();
    }

    @Test
    void reactMode_neverEmitsPlanningOrExecutingStatus() {
        // ReAct mode: one step, direct answer
        AssistantMessage reactMsg = AssistantMessage.builder()
                .content("ReAct 直接回答")
                .build();
        BufferedStreamingResponse reactResponse = new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(reactMsg))), List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(reactResponse);
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false)
        )).thenReturn("ReAct 直接回答");

        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 30);
        AgentRunContext context = buildContext(policy, AgentExecutionMode.REACT);

        ReactRuntimeEngine reactEngine = new ReactRuntimeEngine(context);
        AgentRunResult result = reactEngine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);

        // ReAct must never emit AI_PLANNING or AI_EXECUTING status events
        verify(messageBridge, never()).publishStatusEvent(
                anyString(), anyString(),
                eq(SseMessage.Type.AI_PLANNING), anyString());
        verify(messageBridge, never()).publishStatusEvent(
                anyString(), anyString(),
                eq(SseMessage.Type.AI_EXECUTING), anyString());

        // ReAct must not attach trace metadata
        verify(messageBridge, never()).attachTraceMetadata(anyString(), anyString(), any());
    }

    private AgentRunContext buildContext(AgentRunPolicy policy, AgentExecutionMode mode) {
        return new AgentRunContext(
                "agent-1", "test-agent", "session-1", "turn-1",
                "system prompt", promptLoader, llmService, chatMemory,
                chatOptions, tools, "file summary", "user profile",
                messageBridge, policy, mode, null
        );
    }

    private BufferedStreamingResponse buffered(String content) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content(content)
                .build();
        return new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(assistant))), List.of());
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
