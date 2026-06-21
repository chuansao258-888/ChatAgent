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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeepThinkReflectionEngine}.
 */
@ExtendWith(MockitoExtension.class)
class DeepThinkReflectionEngineTest {

    @Mock private AgentMessageBridge messageBridge;
    @Mock private LLMService llmService;
    @Mock private PromptLoader promptLoader;

    private DeepThinkReflectionEngine engine;

    @BeforeEach
    void setUp() {
        lenient().when(promptLoader.render(anyString(), any())).thenReturn("rendered reflection prompt");
        engine = new DeepThinkReflectionEngine(messageBridge, llmService, promptLoader, true);
    }

    @Test
    void reflect_readyToVerify_returnsResult() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull()
        )).thenReturn(response("""
                {"status":"READY_TO_VERIFY","covered":["S1"],"missing":[],"contradictions":[],"revisedSteps":[]}
                """));

        DeepThinkNotebook notebook = completedNotebook();
        DeepThinkReflectionResult result = engine.reflect("session-1", "turn-1", plan(), notebook, 2, 10);

        assertThat(result.getStatus()).isEqualTo(DeepThinkReflectionResult.READY_TO_VERIFY);
        assertThat(result.getCovered()).containsExactly("S1");
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(result.isSkipped()).isFalse();
        assertThat(notebook.getTotalLlmCalls()).isEqualTo(1);
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                eq(SseMessage.Type.AI_THINKING), anyString());
    }

    @Test
    void reflect_englishPlanUsesEnglishStatusAndInstruction() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull()
        )).thenReturn(response("""
                {"status":"READY_TO_VERIFY","covered":["S1"],"missing":[],"contradictions":[],"revisedSteps":[]}
                """));

        DeepThinkReflectionResult result = engine.reflect("session-1", "turn-1",
                englishPlan(), englishNotebook(), 1, 10);

        assertThat(result.getStatus()).isEqualTo(DeepThinkReflectionResult.READY_TO_VERIFY);
        verify(messageBridge).publishStatusEvent(eq("session-1"), eq("turn-1"),
                eq(SseMessage.Type.AI_THINKING), eq("Reflecting..."));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(messageBridge).collectDecisionResponse(
                eq("session-1"), eq("turn-1"), promptCaptor.capture(), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull());
        assertThat(promptCaptor.getValue().getInstructions())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(UserMessage.class);
                    assertThat(message.getText()).isEqualTo("Output the reflection JSON from the execution results.");
                });
    }

    @Test
    void reflect_revisePlan_returnsOneRevisedStep() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull()
        )).thenReturn(response("""
                {
                  "status":"REVISE_PLAN",
                  "covered":["S1"],
                  "missing":["需要补充来源"],
                  "contradictions":[],
                  "revisedSteps":[{"id":"R1","title":"补充来源","objective":"确认来源","doneCriteria":["有来源"]}]
                }
                """));

        DeepThinkReflectionResult result = engine.reflect("session-1", "turn-1",
                plan(), completedNotebook(), 2, 10);

        assertThat(result.requestsRevision()).isTrue();
        assertThat(result.getRevisedSteps()).hasSize(1);
        assertThat(result.getRevisedSteps().get(0).getId()).isEqualTo("R1");
    }

    @Test
    void reflect_needUserClarification_returnsReason() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull()
        )).thenReturn(response("""
                {
                  "status":"NEED_USER_CLARIFICATION",
                  "covered":[],
                  "missing":["缺少目标范围"],
                  "contradictions":[],
                  "revisedSteps":[],
                  "reasonForUserClarification":"需要用户指定范围"
                }
                """));

        DeepThinkReflectionResult result = engine.reflect("session-1", "turn-1",
                plan(), completedNotebook(), 1, 10);

        assertThat(result.needsUserClarification()).isTrue();
        assertThat(result.getReasonForUserClarification()).isEqualTo("需要用户指定范围");
    }

    @Test
    void reflect_invalidJson_skipsReflection() {
        when(messageBridge.collectDecisionResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull()
        )).thenReturn(response("not json"));

        DeepThinkReflectionResult result = engine.reflect("session-1", "turn-1",
                plan(), completedNotebook(), 1, 10);

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.getStatus()).isEqualTo(DeepThinkReflectionResult.SKIPPED);
        assertThat(result.getMissing()).contains("reflection JSON parse failed");
    }

    @Test
    void reflect_budgetExhausted_skipsWithoutCallingLlm() {
        DeepThinkNotebook notebook = completedNotebook();
        notebook.incrementLlmCalls();

        DeepThinkReflectionResult result = engine.reflect("session-1", "turn-1",
                plan(), notebook, 1, 1);

        assertThat(result.isSkipped()).isTrue();
        verify(messageBridge, never()).collectDecisionResponse(
                anyString(), anyString(), any(Prompt.class), anyString(),
                anyList(), eq(llmService), eq(DecisionVisibility.INTERNAL_TRACE_ONLY),
                eq(true), eq("REFLECT"), isNull());
    }

    private DeepThinkPlan plan() {
        return DeepThinkPlan.builder()
                .goal("回答用户问题")
                .steps(List.of(DeepThinkPlanStep.builder()
                        .id("S1").title("执行").objective("执行步骤").status("COMPLETED")
                        .build()))
                .build();
    }

    private DeepThinkNotebook completedNotebook() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        notebook.recordStepCompletion(DeepThinkPlanStep.builder()
                .id("S1").title("执行").objective("执行步骤").build(), "结论");
        return notebook;
    }

    private DeepThinkPlan englishPlan() {
        return DeepThinkPlan.builder()
                .goal("Diagnose browser test flakiness")
                .steps(List.of(DeepThinkPlanStep.builder()
                        .id("S1").title("Check session state").objective("Compare storage state").status("COMPLETED")
                        .build()))
                .build();
    }

    private DeepThinkNotebook englishNotebook() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        notebook.recordStepCompletion(DeepThinkPlanStep.builder()
                .id("S1").title("Check session state").objective("Compare storage state").build(), "Session state differs");
        return notebook;
    }

    private BufferedStreamingResponse response(String content) {
        AssistantMessage assistant = AssistantMessage.builder().content(content).build();
        return new BufferedStreamingResponse(
                new ChatResponse(List.of(new Generation(assistant))), List.of());
    }
}
