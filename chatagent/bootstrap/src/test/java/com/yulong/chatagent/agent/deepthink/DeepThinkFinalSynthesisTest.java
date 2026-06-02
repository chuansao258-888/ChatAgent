package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.support.dto.AgentTraceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeepThinkFinalSynthesizer}.
 */
@ExtendWith(MockitoExtension.class)
class DeepThinkFinalSynthesisTest {

    @Mock private AgentMessageBridge messageBridge;
    @Mock private LLMService llmService;
    @Mock private PromptLoader promptLoader;

    private ChatMemory chatMemory;
    private ChatOptions chatOptions;

    @BeforeEach
    void setUp() {
        chatMemory = MessageWindowChatMemory.builder().maxMessages(50).build();
        chatMemory.add("session-1", new SystemMessage("system"));
        chatMemory.add("session-1", new UserMessage("用户问题"));
        chatOptions = DefaultToolCallingChatOptions.builder()
                .toolCallbacks(List.of(mock(ToolCallback.class)))
                .internalToolExecutionEnabled(false)
                .build();
        when(promptLoader.render(eq(PromptConstants.DEEPTHINK_FINAL_SYNTHESIS), any()))
                .thenReturn("rendered final prompt");
        when(messageBridge.streamFinalResponse(
                eq("session-1"), eq("turn-1"), any(Prompt.class), eq(llmService), eq(true)
        )).thenReturn("final answer");
    }

    @Test
    void synthesize_streamsFinalAnswerWithToolsDisabledAndTraceAttached() {
        DeepThinkFinalSynthesizer synthesizer = new DeepThinkFinalSynthesizer(
                messageBridge, llmService, promptLoader, chatMemory, chatOptions,
                "session-1", "turn-1", "file summary", "profile summary");

        synthesizer.synthesize(plan(), notebook(), reflectionWithMissingEvidence(), failedVerification());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(messageBridge, times(1)).streamFinalResponse(
                eq("session-1"), eq("turn-1"), promptCaptor.capture(), eq(llmService), eq(true));

        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("rendered final prompt");
        assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        assertThat(options.getToolCallbacks()).isEmpty();

        ArgumentCaptor<AgentTraceMetadata> traceCaptor = ArgumentCaptor.forClass(AgentTraceMetadata.class);
        verify(messageBridge).attachTraceMetadata(eq("session-1"), eq("turn-1"), traceCaptor.capture());
        assertThat(traceCaptor.getValue().getReflection()).isNotNull();
        assertThat(traceCaptor.getValue().getVerification()).isNotNull();
    }

    @Test
    void synthesize_includesCaveatsInPromptVariables() {
        DeepThinkFinalSynthesizer synthesizer = new DeepThinkFinalSynthesizer(
                messageBridge, llmService, promptLoader, chatMemory, chatOptions,
                "session-1", "turn-1", "file summary", "profile summary");

        synthesizer.synthesize(plan(), notebook(), reflectionWithMissingEvidence(), failedVerification());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(promptLoader).render(eq(PromptConstants.DEEPTHINK_FINAL_SYNTHESIS), varsCaptor.capture());

        assertThat(varsCaptor.getValue().get("caveats"))
                .contains("仍缺少：缺少最新来源")
                .contains("验证发现问题");
    }

    private DeepThinkPlan plan() {
        return DeepThinkPlan.builder()
                .goal("回答用户问题")
                .steps(List.of(DeepThinkPlanStep.builder()
                        .id("S1").title("执行").objective("执行步骤").status("COMPLETED")
                        .build()))
                .build();
    }

    private DeepThinkNotebook notebook() {
        DeepThinkNotebook notebook = new DeepThinkNotebook();
        notebook.recordStepCompletion(DeepThinkPlanStep.builder()
                .id("S1").title("执行").objective("执行步骤").build(), "结论");
        return notebook;
    }

    private DeepThinkReflectionResult reflectionWithMissingEvidence() {
        return DeepThinkReflectionResult.builder()
                .status(DeepThinkReflectionResult.READY_TO_VERIFY)
                .covered(List.of("S1"))
                .missing(List.of("缺少最新来源"))
                .contradictions(List.of())
                .rounds(1)
                .build();
    }

    private DeepThinkVerificationResult failedVerification() {
        return DeepThinkVerificationResult.builder()
                .passed(false)
                .issues(List.of(DeepThinkVerificationResult.Issue.builder()
                        .type("MISSING_SOURCE")
                        .claim("缺少来源")
                        .fix("补充 caveat")
                        .build()))
                .requiredFollowUpActions(List.of())
                .caveat("未经完整验证")
                .build();
    }

}
