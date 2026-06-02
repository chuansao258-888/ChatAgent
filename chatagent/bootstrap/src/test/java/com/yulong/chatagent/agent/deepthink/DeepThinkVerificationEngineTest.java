package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.model.SseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeepThinkVerificationEngine}.
 */
@ExtendWith(MockitoExtension.class)
class DeepThinkVerificationEngineTest {

    @Mock private AgentMessageBridge messageBridge;
    @Mock private LLMService llmService;
    @Mock private PromptLoader promptLoader;

    private DeepThinkVerificationEngine engine;

    @BeforeEach
    void setUp() {
        lenient().when(promptLoader.render(anyString(), any())).thenReturn("rendered verification prompt");
        engine = new DeepThinkVerificationEngine(messageBridge, llmService, promptLoader, true);
    }

    @Test
    void verify_passed_returnsPassed() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("VERIFY"), isNull()
        )).thenReturn(response("""
                {"passed":true,"issues":[],"requiredFollowUpActions":[],"caveat":""}
                """));

        DeepThinkNotebook notebook = completedNotebook();
        DeepThinkVerificationResult result = engine.verify("session-1", "turn-1",
                plan(), notebook, reflection(), 1, 10);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.isSkipped()).isFalse();
        assertThat(notebook.getTotalLlmCalls()).isEqualTo(1);
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                eq(SseMessage.Type.AI_THINKING), anyString());
    }

    @Test
    void verify_failedWithFollowUp_returnsAction() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("VERIFY"), isNull()
        )).thenReturn(response("""
                {
                  "passed":false,
                  "issues":[{"type":"MISSING_SOURCE","claim":"缺少来源","fix":"补充来源"}],
                  "requiredFollowUpActions":[{"id":"V1","title":"补充来源","objective":"查找来源"}],
                  "caveat":"来源不足"
                }
                """));

        DeepThinkVerificationResult result = engine.verify("session-1", "turn-1",
                plan(), completedNotebook(), reflection(), 1, 10);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.hasFollowUpActions()).isTrue();
        assertThat(result.getIssues()).hasSize(1);
        assertThat(result.getIssues().get(0).getType()).isEqualTo("MISSING_SOURCE");
        assertThat(result.getRequiredFollowUpActions().get(0).getId()).isEqualTo("V1");
        assertThat(result.getCaveat()).isEqualTo("来源不足");
    }

    @Test
    void verify_invalidJson_returnsSkippedWithCaveat() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("VERIFY"), isNull()
        )).thenReturn(response("not json"));

        DeepThinkVerificationResult result = engine.verify("session-1", "turn-1",
                plan(), completedNotebook(), reflection(), 1, 10);

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.isPassed()).isFalse();
        assertThat(result.getCaveat()).isEqualTo("未经完整验证");
    }

    @Test
    void verify_budgetExhausted_skipsWithoutCallingLlm() {
        DeepThinkNotebook notebook = completedNotebook();
        notebook.incrementLlmCalls();

        DeepThinkVerificationResult result = engine.verify("session-1", "turn-1",
                plan(), notebook, reflection(), 1, 1);

        assertThat(result.isSkipped()).isTrue();
        verify(messageBridge, never()).collectDecisionResponse(
                anyString(), anyString(), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("VERIFY"), isNull());
    }

    private DeepThinkPlan plan() {
        return DeepThinkPlan.builder()
                .goal("回答用户问题")
                .steps(List.of(DeepThinkPlanStep.builder()
                        .id("S1").title("执行").objective("执行步骤").status("COMPLETED")
                        .build()))
                .build();
    }

    private DeepThinkReflectionResult reflection() {
        return DeepThinkReflectionResult.builder()
                .status(DeepThinkReflectionResult.READY_TO_VERIFY)
                .covered(List.of("S1"))
                .missing(List.of())
                .contradictions(List.of())
                .build();
    }

    private DeepThinkNotebook completedNotebook() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        notebook.recordStepCompletion(DeepThinkPlanStep.builder()
                .id("S1").title("执行").objective("执行步骤").build(), "结论");
        return notebook;
    }

    private BufferedStreamingResponse response(String content) {
        AssistantMessage assistant = AssistantMessage.builder().content(content).build();
        return new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(assistant))), List.of());
    }
}
