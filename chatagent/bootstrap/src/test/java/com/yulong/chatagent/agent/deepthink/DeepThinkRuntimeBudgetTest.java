package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.*;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentRunContext;
import com.yulong.chatagent.agent.runtime.AgentRunPolicy;
import com.yulong.chatagent.agent.runtime.AgentRunPolicyProperties;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.model.SseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DeepThinkRuntimeEngine} — budget enforcement, fallback, full run.
 */
@ExtendWith(MockitoExtension.class)
class DeepThinkRuntimeBudgetTest {

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
        chatMemory.add("session-1", new UserMessage("用户问题"));
        chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false).build();
        tools = List.of(mockToolCallback("knowledgeQuery"));

        lenient().when(promptLoader.load(anyString())).thenReturn("fallback prompt");
        lenient().when(promptLoader.render(anyString(), any())).thenReturn("rendered prompt");
    }

    @Test
    void planParseFails_fallsBackToReact() {
        // Planner returns unparseable text
        AssistantMessage planMsg = AssistantMessage.builder()
                .content("Sorry, I cannot produce a plan for this.")
                .build();
        ChatResponse planResponse = new ChatResponse(List.of(new Generation(planMsg)));
        BufferedStreamingResponse planBuffered = new BufferedStreamingResponse(planResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(planBuffered);

        // ReAct fallback: one step with direct answer
        AssistantMessage reactMsg = AssistantMessage.builder()
                .content("Direct answer via ReAct fallback")
                .build();
        ChatResponse reactResponse = new ChatResponse(List.of(new Generation(reactMsg)));
        BufferedStreamingResponse reactBuffered = new BufferedStreamingResponse(reactResponse, List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull()
        )).thenReturn(reactBuffered);
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false)
        )).thenReturn("Direct answer via ReAct fallback");

        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 30);
        AgentRunContext context = buildContext(policy);

        DeepThinkRuntimeEngine engine = new DeepThinkRuntimeEngine(context);
        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        // Verify planner was called
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                eq(SseMessage.Type.AI_PLANNING), anyString());
        // Verify ReAct fallback was invoked through internal decision collection.
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false), isNull(), isNull());
    }

    @Test
    void budgetExhausted_forcesFinalSynthesis() {
        // Plan: 2 steps
        String planJson = """
                {"goal":"test","complexity":"LOW","steps":[
                  {"id":"S1","title":"Step 1","objective":"Do step 1"},
                  {"id":"S2","title":"Step 2","objective":"Do step 2"}
                ]}
                """;

        AssistantMessage planMsg = AssistantMessage.builder()
                .content(planJson).build();
        BufferedStreamingResponse planBuffered = new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(planMsg))), List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(planBuffered);

        // Step 1: returns conclusion
        AssistantMessage step1Msg = AssistantMessage.builder()
                .content("Step 1 completed").build();
        BufferedStreamingResponse step1Buffered = new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(step1Msg))), List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(step1Buffered);

        // Budget: maxTotalLlmCalls=2 (planner uses 1, step 1 uses 1) → step 2 should be skipped
        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 2);
        AgentRunContext context = buildContext(policy);

        DeepThinkRuntimeEngine engine = new DeepThinkRuntimeEngine(context);
        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.PARTIAL);
        assertThat(result.errorType()).isEqualTo("LLM_DECISION_BUDGET_EXHAUSTED");
        verify(messageBridge, never()).streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), anyBoolean());
    }

    @Test
    void fullDeepThinkRun_planExecuteFinal() {
        // Plan: 1 step
        String planJson = """
                {"goal":"查找信息","complexity":"MEDIUM","steps":[
                  {"id":"S1","title":"搜索","objective":"搜索相关信息","doneCriteria":["获得结果"]}
                ]}
                """;

        AssistantMessage planMsg = AssistantMessage.builder()
                .content(planJson).build();
        BufferedStreamingResponse planBuffered = new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(planMsg))), List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(planBuffered);

        // Step 1: tool call
        AssistantMessage toolCallMsg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "knowledgeQuery", "{\"query\":\"test\"}")))
                .build();
        BufferedStreamingResponse toolCallBuffered = new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(toolCallMsg))), List.of());

        // Step 1: conclusion after tool
        AssistantMessage conclusionMsg = AssistantMessage.builder()
                .content("找到了相关信息：结果是 X").build();
        BufferedStreamingResponse conclusionBuffered = new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(conclusionMsg))), List.of());

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(toolCallBuffered).thenReturn(conclusionBuffered);

        // Final synthesis
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), anyBoolean()
        )).thenReturn("综合回答");

        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 30);
        AgentRunContext context = buildContext(policy);

        DeepThinkRuntimeEngine engine = new DeepThinkRuntimeEngine(context);
        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.PARTIAL);

        // Verify status events published
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                eq(SseMessage.Type.AI_PLANNING), anyString());
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                eq(SseMessage.Type.AI_EXECUTING), anyString());

        // Verify tool responses persisted
        verify(messageBridge).persistInternalToolResponses(
                eq("session-1"), eq("turn-1"), any(),
                eq("EXECUTE"), eq("S1"));

        // Verify final answer streamed
        verify(messageBridge).streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), anyBoolean());
    }

    @Test
    void fullDeepThinkRun_reflectionRevisionAndVerificationFollowUp_executeBoundedSteps() {
        String planJson = """
                {"goal":"回答问题","complexity":"HIGH","steps":[
                  {"id":"S1","title":"初始步骤","objective":"先收集基础结论"}
                ]}
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(buffered(planJson));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(buffered("S1 结论"));

        String reflectionJson = """
                {
                  "status":"REVISE_PLAN",
                  "covered":["S1"],
                  "missing":["需要补充来源"],
                  "contradictions":[],
                  "revisedSteps":[{"id":"R1","title":"补充来源","objective":"补充来源"}]
                }
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("REFLECT"), isNull()
        )).thenReturn(buffered(reflectionJson));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("R1")
        )).thenReturn(buffered("R1 结论"));

        String verificationJson = """
                {
                  "passed":false,
                  "issues":[{"type":"MISSING_SOURCE","claim":"仍缺少二次验证","fix":"补充验证"}],
                  "requiredFollowUpActions":[{"id":"V1","title":"二次验证","objective":"验证来源"}],
                  "caveat":"仍需说明不确定性"
                }
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("VERIFY"), isNull()
        )).thenReturn(buffered(verificationJson)).thenReturn(buffered("""
                {"passed":true,"issues":[],"requiredFollowUpActions":[]}
                """));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("V1")
        )).thenReturn(buffered("V1 结论"));

        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false)
        )).thenReturn("综合回答");

        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 10, 1, 2);
        AgentRunContext context = buildContext(policy);

        DeepThinkRuntimeEngine engine = new DeepThinkRuntimeEngine(context);
        AgentRunResult result = engine.run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        verify(messageBridge, times(2)).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("VERIFY"), isNull());
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("R1"));
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("V1"));
        verify(messageBridge).attachTraceMetadata(eq("session-1"), eq("turn-1"), any());
    }

    @Test
    void fullDeepThinkRun_usesPerStageDeepThinkingPolicy() {
        String planJson = """
                {"goal":"回答问题","complexity":"MEDIUM","steps":[
                  {"id":"S1","title":"执行","objective":"执行步骤"}
                ]}
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("PLAN"), isNull()
        )).thenReturn(buffered(planJson));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(buffered("S1 结论"));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("REFLECT"), isNull()
        )).thenReturn(buffered("""
                {"status":"READY_TO_VERIFY","covered":["S1"],"missing":[],"contradictions":[],"revisedSteps":[]}
                """));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("VERIFY"), isNull()
        )).thenReturn(buffered("""
                {"passed":true,"issues":[],"requiredFollowUpActions":[],"caveat":""}
                """));

        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false)
        )).thenReturn("综合回答");

        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getDeepthink().setPlanningModelDeepThinking(false);
        properties.getDeepthink().setExecutionModelDeepThinking(true);
        properties.getDeepthink().setReflectionModelDeepThinking(false);
        properties.getDeepthink().setVerificationModelDeepThinking(false);
        AgentRunPolicy policy = AgentRunPolicy.deepthink(properties);

        assertThat(policy.isPlanningModelDeepThinking()).isFalse();
        assertThat(policy.isExecutionModelDeepThinking()).isTrue();
        assertThat(policy.isReflectionModelDeepThinking()).isFalse();
        assertThat(policy.isVerificationModelDeepThinking()).isFalse();

        AgentRunContext context = buildContext(policy);
        AgentRunResult result = new DeepThinkRuntimeEngine(context).run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.SUCCESS);
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("PLAN"), isNull());
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("EXECUTE"), eq("S1"));
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("REFLECT"), isNull());
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("VERIFY"), isNull());
    }

    @Test
    void stepFailure_triggersEarlyReflectionAndSkipsRemainingPlanSteps() {
        String planJson = """
                {"goal":"回答问题","complexity":"HIGH","steps":[
                  {"id":"S1","title":"失败步骤","objective":"会失败"},
                  {"id":"S2","title":"后续步骤","objective":"不应继续执行"}
                ]}
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(buffered(planJson));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenThrow(new RuntimeException("step failed"));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("REFLECT"), isNull()
        )).thenReturn(buffered("""
                {"status":"READY_TO_VERIFY","covered":[],"missing":["S1 failed"],"contradictions":[],"revisedSteps":[]}
                """));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("VERIFY"), isNull()
        )).thenReturn(buffered("""
                {"passed":false,"issues":[],"requiredFollowUpActions":[],"caveat":"部分步骤失败"}
                """));

        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(false)
        )).thenReturn("综合回答");

        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 10, 1, 1);
        AgentRunContext context = buildContext(policy);

        AgentRunResult result = new DeepThinkRuntimeEngine(context).run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.PARTIAL);
        verify(messageBridge, never()).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S2"));
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("REFLECT"), isNull());
    }

    private AgentRunContext buildContext(AgentRunPolicy policy) {
        return new AgentRunContext(
                "agent-1", "test-agent", "session-1", "turn-1",
                "system prompt", promptLoader, llmService, chatMemory,
                chatOptions, tools, "file summary", "user profile",
                messageBridge, policy, AgentExecutionMode.DEEPTHINK, null
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

    @Test
    void reflectionNeedsUserClarification_skipsVerification_proceedsToFinalSynthesis() {
        String planJson = """
                {"goal":"回答问题","complexity":"MEDIUM","steps":[
                  {"id":"S1","title":"收集信息","objective":"搜索相关信息"}
                ]}
                """;
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("PLAN"), isNull()
        )).thenReturn(buffered(planJson));

        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(false),
                eq("EXECUTE"), eq("S1")
        )).thenReturn(buffered("S1 结论"));

        // Reflection returns NEED_USER_CLARIFICATION
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), eq(true),
                eq("REFLECT"), isNull()
        )).thenReturn(buffered("""
                {"status":"NEED_USER_CLARIFICATION","covered":["S1"],"missing":[],"contradictions":[],"revisedSteps":[],"reasonForUserClarification":"需要用户确认范围"}
                """));

        // Final synthesis should still be called
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), anyBoolean()
        )).thenReturn("需要您澄清一些问题");

        AgentRunPolicy policy = new AgentRunPolicy(20, 4, 5, 3, 20, 10, 1, 1);
        AgentRunContext context = buildContext(policy);

        AgentRunResult result = new DeepThinkRuntimeEngine(context).run(context);

        assertThat(result.status()).isEqualTo(AgentRunResult.Status.PARTIAL);

        // Verification should NOT be called when clarification is needed
        verify(messageBridge, never()).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService),
                eq(DecisionVisibility.INTERNAL_TRACE_ONLY), anyBoolean(),
                eq("VERIFY"), any());

        // Final synthesis should still produce an answer
        verify(messageBridge).streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), anyBoolean());

        // Trace should still be attached
        verify(messageBridge).attachTraceMetadata(eq("session-1"), eq("turn-1"), any());
    }
}
