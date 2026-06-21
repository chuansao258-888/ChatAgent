package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.support.dto.AgentTraceMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Streams the final DeepThink answer with tools disabled and attaches sanitized trace metadata.
 */
@Slf4j
public class DeepThinkFinalSynthesizer {

    private final AgentMessageBridge messageBridge;
    private final LLMService llmService;
    private final PromptLoader promptLoader;
    private final ChatMemory chatMemory;
    private final ChatOptions chatOptions;
    private final String chatSessionId;
    private final String turnId;
    private final String sessionFileSummary;
    private final String relevantLongTermMemories;

    public DeepThinkFinalSynthesizer(AgentMessageBridge messageBridge,
                                     LLMService llmService,
                                     PromptLoader promptLoader,
                                     ChatMemory chatMemory,
                                     ChatOptions chatOptions,
                                     String chatSessionId,
                                     String turnId,
                                     String sessionFileSummary,
                                     String relevantLongTermMemories) {
        this.messageBridge = messageBridge;
        this.llmService = llmService;
        this.promptLoader = promptLoader;
        this.chatMemory = chatMemory;
        this.chatOptions = chatOptions;
        this.chatSessionId = chatSessionId;
        this.turnId = turnId;
        this.sessionFileSummary = sessionFileSummary;
        this.relevantLongTermMemories = relevantLongTermMemories;
    }

    public void synthesize(DeepThinkPlan plan,
                           DeepThinkNotebook notebook,
                           DeepThinkReflectionResult reflectionResult,
                           DeepThinkVerificationResult verificationResult) {
        log.info("DeepThink final synthesis: observations={}, reflectionStatus={}, verificationPassed={}",
                notebook.getCompletedSteps().size(),
                reflectionResult == null ? null : reflectionResult.getStatus(),
                verificationResult == null ? null : verificationResult.isPassed());
        try {
            AgentTraceMetadata trace = DeepThinkTraceSanitizer.buildTrace(
                    "DEEPTHINK", plan, notebook, reflectionResult, verificationResult);
            Prompt prompt = buildPrompt(plan, notebook, reflectionResult, verificationResult);
            messageBridge.streamFinalResponse(chatSessionId, turnId, prompt, llmService, false);
            messageBridge.attachTraceMetadata(chatSessionId, turnId, trace);
        } catch (Exception e) {
            log.error("Failed to produce DeepThink final synthesis", e);
            throw new AgentRunException(
                    "Failed to produce DeepThink final answer",
                    e,
                    AgentRunResult.failure(0, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
            );
        }
    }

    private Prompt buildPrompt(DeepThinkPlan plan,
                               DeepThinkNotebook notebook,
                               DeepThinkReflectionResult reflectionResult,
                               DeepThinkVerificationResult verificationResult) {
        ChatOptions streamOptions = this.chatOptions.copy();
        if (streamOptions instanceof ToolCallingChatOptions toolOptions) {
            toolOptions.setToolCallbacks(List.of());
        }

        List<Message> promptMessages = this.chatMemory.get(this.chatSessionId);
        String finalAnswerPrompt = promptLoader.render(PromptConstants.DEEPTHINK_FINAL_SYNTHESIS, Map.of(
                "sessionFileSummary", sessionFileSummary == null ? "" : sessionFileSummary,
                "relevantLongTermMemories", relevantLongTermMemories == null ? "" : relevantLongTermMemories,
                "goal", plan == null || plan.getGoal() == null ? "" : plan.getGoal(),
                "observations", notebook.buildObservationsSummary(),
                "reflectionSummary", summarizeReflection(reflectionResult),
                "verificationSummary", summarizeVerification(verificationResult),
                "caveats", buildCaveats(reflectionResult, verificationResult, prefersChinese(promptMessages))
        ));

        List<Message> finalPromptMessages = new ArrayList<>(promptMessages.size() + 1);
        finalPromptMessages.add(new SystemMessage(finalAnswerPrompt));
        finalPromptMessages.addAll(promptMessages);

        return Prompt.builder()
                .chatOptions(streamOptions)
                .messages(finalPromptMessages)
                .build();
    }

    private String summarizeReflection(DeepThinkReflectionResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("status=").append(result.getStatus()).append("\n");
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

    private String summarizeVerification(DeepThinkVerificationResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("passed=").append(result.isPassed()).append("\n");
        if (result.getCaveat() != null) {
            sb.append("caveat=").append(result.getCaveat()).append("\n");
        }
        if (result.getIssues() != null) {
            for (DeepThinkVerificationResult.Issue issue : result.getIssues()) {
                sb.append("- ")
                        .append(issue.getType())
                        .append(": ")
                        .append(issue.getClaim() == null ? "" : issue.getClaim())
                        .append(" fix=")
                        .append(issue.getFix() == null ? "" : issue.getFix())
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private String buildCaveats(DeepThinkReflectionResult reflectionResult,
                                DeepThinkVerificationResult verificationResult,
                                boolean preferChinese) {
        List<String> caveats = new ArrayList<>();
        if (reflectionResult != null && reflectionResult.needsUserClarification()) {
            caveats.add((preferChinese ? "需要向用户澄清：" : "Clarification needed from the user: ")
                    + reflectionResult.getReasonForUserClarification());
        }
        if (reflectionResult != null && reflectionResult.getMissing() != null && !reflectionResult.getMissing().isEmpty()) {
            caveats.add((preferChinese ? "仍缺少：" : "Still missing: ")
                    + String.join("; ", reflectionResult.getMissing()));
        }
        if (verificationResult != null && verificationResult.getCaveat() != null) {
            caveats.add(verificationResult.getCaveat());
        }
        if (verificationResult != null && !verificationResult.isPassed()
                && verificationResult.getIssues() != null && !verificationResult.getIssues().isEmpty()) {
            caveats.add(preferChinese
                    ? "验证发现问题，最终回答需要明确不确定性。"
                    : "Verification found issues; the final answer must state uncertainty.");
        }
        return caveats.isEmpty() ? (preferChinese ? "无" : "None") : String.join("\n", caveats);
    }

    private boolean prefersChinese(List<Message> promptMessages) {
        String latestUserText = "";
        for (int i = promptMessages.size() - 1; i >= 0; i--) {
            if (promptMessages.get(i) instanceof UserMessage userMessage
                    && userMessage.getText() != null
                    && !userMessage.getText().isBlank()) {
                latestUserText = userMessage.getText();
                break;
            }
        }
        return DeepThinkLanguageSupport.prefersChinese(latestUserText);
    }
}
