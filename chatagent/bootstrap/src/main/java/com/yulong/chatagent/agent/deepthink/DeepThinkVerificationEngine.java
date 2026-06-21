package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.model.SseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * DeepThink verification stage. It validates the planned answer against
 * observations without exposing raw internal messages to the user.
 */
@Slf4j
public class DeepThinkVerificationEngine {

    private final AgentMessageBridge messageBridge;
    private final LLMService llmService;
    private final PromptLoader promptLoader;
    private final boolean deepThinking;

    public DeepThinkVerificationEngine(AgentMessageBridge messageBridge,
                                       LLMService llmService,
                                       PromptLoader promptLoader,
                                       boolean deepThinking) {
        this.messageBridge = messageBridge;
        this.llmService = llmService;
        this.promptLoader = promptLoader;
        this.deepThinking = deepThinking;
    }

    public DeepThinkVerificationResult verify(String chatSessionId,
                                              String turnId,
                                              DeepThinkPlan plan,
                                              DeepThinkNotebook notebook,
                                              DeepThinkReflectionResult reflectionResult,
                                              int maxVerificationRounds,
                                              int maxTotalLlmCalls) {
        String languageSource = DeepThinkLanguageSupport.planLanguageSource(plan);
        boolean preferChinese = DeepThinkLanguageSupport.prefersChinese(languageSource);
        if (maxVerificationRounds <= 0) {
            return DeepThinkVerificationResult.skipped("verification disabled");
        }
        if (maxTotalLlmCalls > 0 && notebook.getTotalLlmCalls() >= maxTotalLlmCalls) {
            log.warn("Skipping verification: LLM budget exhausted ({}>={})",
                    notebook.getTotalLlmCalls(), maxTotalLlmCalls);
            return DeepThinkVerificationResult.skipped("LLM budget exhausted before verification");
        }

        messageBridge.publishStatusEvent(chatSessionId, turnId,
                SseMessage.Type.AI_THINKING, preferChinese ? "正在验证..." : "Verifying...");

        String systemPrompt = buildSystemPrompt(plan, notebook, reflectionResult);
        BufferedStreamingResponse response = messageBridge.collectDecisionResponse(
                chatSessionId,
                turnId,
                new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(preferChinese
                                ? "请根据观察结果输出验证 JSON。"
                                : "Output the verification JSON from the observations.")
                )),
                systemPrompt,
                List.of(),
                llmService,
                DecisionVisibility.INTERNAL_TRACE_ONLY,
                deepThinking,
                "VERIFY",
                null
        );
        notebook.incrementLlmCalls();

        DeepThinkVerificationResult parsed = DeepThinkJsonParser.parseVerificationResult(extractText(response));
        if (parsed == null) {
            log.warn("DeepThink verification JSON parse failed; final answer will include verification caveat");
            return DeepThinkVerificationResult.skipped(preferChinese ? "未经完整验证" : "Not fully verified");
        }
        return parsed;
    }

    private String buildSystemPrompt(DeepThinkPlan plan,
                                     DeepThinkNotebook notebook,
                                     DeepThinkReflectionResult reflectionResult) {
        Map<String, String> vars = Map.of(
                "goal", plan == null || plan.getGoal() == null ? "" : plan.getGoal(),
                "observations", notebook.buildObservationsSummary(),
                "reflectionSummary", summarizeReflection(reflectionResult)
        );
        return promptLoader.render(PromptConstants.DEEPTHINK_VERIFICATION, vars);
    }

    private String summarizeReflection(DeepThinkReflectionResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(result.getStatus()).append("\n");
        if (result.getMissing() != null && !result.getMissing().isEmpty()) {
            sb.append("missing=").append(String.join("; ", result.getMissing())).append("\n");
        }
        if (result.getContradictions() != null && !result.getContradictions().isEmpty()) {
            sb.append("contradictions=").append(String.join("; ", result.getContradictions())).append("\n");
        }
        if (result.getReasonForUserClarification() != null) {
            sb.append("clarification=").append(result.getReasonForUserClarification()).append("\n");
        }
        return sb.toString();
    }

    private String extractText(BufferedStreamingResponse response) {
        if (response == null || response.response() == null) {
            return null;
        }
        ChatResponse chatResponse = response.response();
        return chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;
    }
}
