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
 * DeepThink reflection stage. It inspects completed observations and decides
 * whether the run can verify, needs one bounded revision, or should ask the user.
 */
@Slf4j
public class DeepThinkReflectionEngine {

    private final AgentMessageBridge messageBridge;
    private final LLMService llmService;
    private final PromptLoader promptLoader;
    private final boolean deepThinking;

    public DeepThinkReflectionEngine(AgentMessageBridge messageBridge,
                                     LLMService llmService,
                                     PromptLoader promptLoader,
                                     boolean deepThinking) {
        this.messageBridge = messageBridge;
        this.llmService = llmService;
        this.promptLoader = promptLoader;
        this.deepThinking = deepThinking;
    }

    public DeepThinkReflectionResult reflect(String chatSessionId,
                                             String turnId,
                                             DeepThinkPlan plan,
                                             DeepThinkNotebook notebook,
                                             int maxReflectionRounds,
                                             int maxTotalLlmCalls) {
        int rounds = Math.max(0, maxReflectionRounds);
        if (rounds == 0) {
            return DeepThinkReflectionResult.skipped("reflection disabled");
        }

        DeepThinkReflectionResult last = null;
        for (int round = 1; round <= rounds; round++) {
            if (maxTotalLlmCalls > 0 && notebook.getTotalLlmCalls() >= maxTotalLlmCalls) {
                log.warn("Skipping reflection: LLM budget exhausted ({}>={})",
                        notebook.getTotalLlmCalls(), maxTotalLlmCalls);
                return DeepThinkReflectionResult.skipped("LLM budget exhausted before reflection");
            }

            messageBridge.publishStatusEvent(chatSessionId, turnId,
                    SseMessage.Type.AI_THINKING, "正在反思...");

            String systemPrompt = buildSystemPrompt(plan, notebook, round, rounds);
            BufferedStreamingResponse response = messageBridge.collectDecisionResponse(
                    chatSessionId,
                    turnId,
                    new Prompt(List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage("请基于执行结果输出反思 JSON。")
                    )),
                    systemPrompt,
                    List.of(),
                    llmService,
                    DecisionVisibility.INTERNAL_TRACE_ONLY,
                    deepThinking,
                    "REFLECT",
                    null
            );
            notebook.incrementLlmCalls();

            String content = extractText(response);
            DeepThinkReflectionResult parsed = DeepThinkJsonParser.parseReflectionResult(content);
            if (parsed == null) {
                log.warn("DeepThink reflection JSON parse failed; skipping reflection");
                return DeepThinkReflectionResult.skipped("reflection JSON parse failed");
            }
            parsed.setRounds(round);
            last = parsed;

            if (!DeepThinkReflectionResult.CONTINUE.equals(parsed.getStatus())) {
                return parsed;
            }
        }

        return last != null ? last : DeepThinkReflectionResult.skipped("reflection produced no result");
    }

    private String buildSystemPrompt(DeepThinkPlan plan,
                                     DeepThinkNotebook notebook,
                                     int round,
                                     int maxRounds) {
        Map<String, String> vars = Map.of(
                "goal", safe(plan == null ? null : plan.getGoal()),
                "planSummary", summarizePlan(plan),
                "observations", safe(notebook.buildObservationsSummary()),
                "round", String.valueOf(round),
                "maxRounds", String.valueOf(maxRounds)
        );
        return promptLoader.render(PromptConstants.DEEPTHINK_REFLECTION, vars);
    }

    private String summarizePlan(DeepThinkPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DeepThinkPlanStep step : plan.getSteps()) {
            sb.append("- ")
                    .append(safe(step.getId()))
                    .append(": ")
                    .append(safe(step.getTitle()))
                    .append(" [")
                    .append(safe(step.getStatus()))
                    .append("]\n");
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
