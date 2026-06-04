package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.AgentState;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.AgentRunContext;
import com.yulong.chatagent.agent.runtime.AgentRunPolicy;
import com.yulong.chatagent.agent.runtime.AgentRuntimeEngine;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.ReactRuntimeEngine;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepThink runtime engine.
 *
 * <p>Flow: Plan -> Execute -> Reflect -> Verify -> Final Answer.
 * Internal decision calls use internal trace-only visibility; final answer is
 * streamed exactly once with tools disabled by {@link DeepThinkFinalSynthesizer}.</p>
 */
@Slf4j
public class DeepThinkRuntimeEngine implements AgentRuntimeEngine {

    private final AgentRunPolicy policy;
    private final AgentMessageBridge messageBridge;
    private final ChatMemory chatMemory;
    private final String chatSessionId;
    private final String agentId;
    private final ChatOptions chatOptions;
    private final PromptLoader promptLoader;
    private final LLMService llmService;
    private final List<ToolCallback> availableTools;
    private final String sessionFileSummary;
    private final String relevantLongTermMemories;
    private final String turnId;

    private AgentState agentState;

    public DeepThinkRuntimeEngine(AgentRunContext context) {
        this.policy = context.policy();
        this.chatMemory = context.chatMemory();
        this.chatSessionId = context.chatSessionId();
        this.agentId = context.agentId();
        this.chatOptions = context.chatOptions();
        this.promptLoader = context.promptLoader();
        this.llmService = context.llmService();
        this.availableTools = context.availableTools();
        this.sessionFileSummary = context.sessionFileSummary();
        this.relevantLongTermMemories = context.relevantLongTermMemories();
        this.turnId = context.turnId();
        this.messageBridge = context.messageBridge();
        this.agentState = AgentState.IDLE;
    }

    @Override
    public AgentRunResult run(AgentRunContext context) {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        long startTime = System.nanoTime();
        log.info("DeepThink run started: traceId={}, agentId={}, sessionId={}",
                TraceContext.getTraceId(), agentId, chatSessionId);

        try {
            agentState = AgentState.PLANNING;
            DeepThinkNotebook notebook = new DeepThinkNotebook();

            DeepThinkPlanner planner = new DeepThinkPlanner(
                    messageBridge, llmService, availableTools,
                    policy.isPlanningModelDeepThinking(), promptLoader);

            String sessionContext = buildSessionContext();
            String userQuestion = extractUserQuestion();

            DeepThinkPlan plan = planner.plan(
                    chatSessionId, turnId, userQuestion, sessionContext,
                    policy.getMaxPlanItems());

            notebook.incrementLlmCalls();

            if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
                log.warn("DeepThink plan parse failed or empty, falling back to ReAct");
                return fallbackToReact(context, startTime);
            }

            log.info("DeepThink plan generated: goal='{}', steps={}",
                    truncate(plan.getGoal(), 50), plan.getSteps().size());

            agentState = AgentState.EXECUTING;
            DeepThinkStepExecutor stepExecutor = new DeepThinkStepExecutor(
                    messageBridge, llmService, availableTools,
                    policy.isExecutionModelDeepThinking(), promptLoader);

            executePlannedSteps(plan, notebook, stepExecutor);

            agentState = AgentState.THINKING;
            DeepThinkReflectionResult reflectionResult = reflect(plan, notebook);
            if (reflectionResult.requestsRevision()) {
                executeFollowUpStep("reflection", plan, notebook, stepExecutor,
                        reflectionResult.getRevisedSteps().get(0), "R1");
            }

            DeepThinkVerificationResult verificationResult;
            if (reflectionResult.needsUserClarification()) {
                verificationResult = DeepThinkVerificationResult.skipped("等待用户澄清，未执行验证");
            } else {
                verificationResult = verify(plan, notebook, reflectionResult);
                if (verificationResult.hasFollowUpActions()) {
                    executeFollowUpStep("verification", plan, notebook, stepExecutor,
                            verificationResult.getRequiredFollowUpActions().get(0), "V1");
                }
            }

            DeepThinkFinalSynthesizer finalSynthesizer = new DeepThinkFinalSynthesizer(
                    messageBridge, llmService, promptLoader, chatMemory, chatOptions,
                    chatSessionId, turnId, sessionFileSummary, relevantLongTermMemories);
            finalSynthesizer.synthesize(plan, notebook, reflectionResult, verificationResult);

            agentState = AgentState.FINISHED;
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("DeepThink run finished: traceId={}, agentId={}, sessionId={}, " +
                            "steps={}, toolCalls={}, llmCalls={}, durationMs={}",
                    TraceContext.getTraceId(), agentId, chatSessionId,
                    notebook.getCompletedSteps().size(),
                    notebook.getTotalToolCalls(), notebook.getTotalLlmCalls(), durationMs);

            return AgentRunResult.success(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit());

        } catch (AgentRunException e) {
            throw e;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.error("DeepThink run error: traceId={}, agentId={}, sessionId={}, durationMs={}",
                    TraceContext.getTraceId(), agentId, chatSessionId, durationMs, e);
            throw new AgentRunException(
                    "Error running DeepThink agent",
                    e,
                    AgentRunResult.failure(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
            );
        }
    }

    private void executePlannedSteps(DeepThinkPlan plan,
                                     DeepThinkNotebook notebook,
                                     DeepThinkStepExecutor stepExecutor) {
        for (int i = 0; i < plan.getSteps().size(); i++) {
            DeepThinkPlanStep step = plan.getSteps().get(i);

            if (!hasLlmBudget(notebook)) {
                log.warn("DeepThink LLM call budget exhausted at step {}", step.getId());
                markStepSkipped(plan, i);
                break;
            }
            if (!hasToolBudget(notebook)) {
                log.warn("DeepThink tool call budget exhausted at step {}", step.getId());
                markStepSkipped(plan, i);
                break;
            }

            boolean completed = executeStep(stepExecutor, plan, notebook, step);
            if (!completed) {
                log.warn("DeepThink step {} failed; entering reflection early", step.getId());
                markStepSkipped(plan, i + 1);
                break;
            }
        }
    }

    private DeepThinkReflectionResult reflect(DeepThinkPlan plan, DeepThinkNotebook notebook) {
        DeepThinkReflectionEngine reflectionEngine = new DeepThinkReflectionEngine(
                messageBridge, llmService, promptLoader,
                policy.isReflectionModelDeepThinking());
        return reflectionEngine.reflect(
                chatSessionId, turnId, plan, notebook,
                policy.getMaxReflectionRounds(), policy.getMaxTotalLlmCalls());
    }

    private DeepThinkVerificationResult verify(DeepThinkPlan plan,
                                               DeepThinkNotebook notebook,
                                               DeepThinkReflectionResult reflectionResult) {
        DeepThinkVerificationEngine verificationEngine = new DeepThinkVerificationEngine(
                messageBridge, llmService, promptLoader,
                policy.isVerificationModelDeepThinking());
        return verificationEngine.verify(
                chatSessionId, turnId, plan, notebook, reflectionResult,
                policy.getMaxVerificationRounds(), policy.getMaxTotalLlmCalls());
    }

    private void executeFollowUpStep(String source,
                                     DeepThinkPlan plan,
                                     DeepThinkNotebook notebook,
                                     DeepThinkStepExecutor stepExecutor,
                                     DeepThinkPlanStep step,
                                     String fallbackId) {
        if (step == null) {
            return;
        }
        if (!hasLlmBudget(notebook)) {
            log.warn("Skipping {} follow-up step: LLM budget exhausted", source);
            step.setStatus("SKIPPED");
            appendPlanStep(plan, step);
            return;
        }
        if (!hasToolBudget(notebook)) {
            log.warn("Skipping {} follow-up step: tool budget exhausted", source);
            step.setStatus("SKIPPED");
            appendPlanStep(plan, step);
            return;
        }

        normalizeFollowUpStep(step, fallbackId);
        appendPlanStep(plan, step);
        executeStep(stepExecutor, plan, notebook, step);
    }

    private boolean executeStep(DeepThinkStepExecutor stepExecutor,
                                DeepThinkPlan plan,
                                DeepThinkNotebook notebook,
                                DeepThinkPlanStep step) {
        try {
            String observations = notebook.buildObservationsSummary();
            String conclusion = stepExecutor.executeStep(
                    chatSessionId, turnId, step,
                    policy.getMaxReactStepsPerPlanItem(),
                    notebook, plan.getGoal(), observations,
                    policy.getMaxTotalToolCalls(), policy.getMaxTotalLlmCalls());

            notebook.recordStepCompletion(step, conclusion);
            step.setStatus("COMPLETED");
            step.setConclusion(conclusion);
            return true;
        } catch (Exception e) {
            log.warn("Step {} execution failed: {}", step.getId(), e.getMessage());
            notebook.recordStepFailure(step);
            step.setStatus("FAILED");
            return false;
        }
    }

    private void appendPlanStep(DeepThinkPlan plan, DeepThinkPlanStep step) {
        if (plan == null || step == null) {
            return;
        }
        List<DeepThinkPlanStep> existing = plan.getSteps() == null
                ? List.of()
                : plan.getSteps();
        if (existing.stream().anyMatch(candidate -> candidate == step)) {
            return;
        }
        List<DeepThinkPlanStep> updated = new ArrayList<>(existing);
        updated.add(step);
        plan.setSteps(updated);
    }

    private void normalizeFollowUpStep(DeepThinkPlanStep step, String fallbackId) {
        if (step.getId() == null || step.getId().isBlank()) {
            step.setId(fallbackId);
        }
        if (step.getTitle() == null || step.getTitle().isBlank()) {
            step.setTitle("补充验证");
        }
        if (step.getObjective() == null || step.getObjective().isBlank()) {
            step.setObjective(step.getTitle());
        }
    }

    private boolean hasLlmBudget(DeepThinkNotebook notebook) {
        return policy.getMaxTotalLlmCalls() <= 0
                || notebook.getTotalLlmCalls() < policy.getMaxTotalLlmCalls();
    }

    private boolean hasToolBudget(DeepThinkNotebook notebook) {
        return policy.getMaxTotalToolCalls() <= 0
                || notebook.getTotalToolCalls() < policy.getMaxTotalToolCalls();
    }

    /**
     * Planner parse failure falls back to standard ReAct.
     */
    private AgentRunResult fallbackToReact(AgentRunContext context, long startTime) {
        log.info("Falling back to ReactRuntimeEngine");
        try {
            ReactRuntimeEngine reactEngine = new ReactRuntimeEngine(context);
            return reactEngine.run(context);
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            throw new AgentRunException(
                    "DeepThink fallback to ReAct also failed",
                    e,
                    AgentRunResult.failure(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
            );
        }
    }

    /**
     * Extract latest user question text.
     */
    private String extractUserQuestion() {
        List<Message> messages = this.chatMemory.get(this.chatSessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof org.springframework.ai.chat.messages.UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return "";
    }

    /**
     * Build session context summary.
     */
    private String buildSessionContext() {
        StringBuilder sb = new StringBuilder();
        if (sessionFileSummary != null && !sessionFileSummary.isBlank()) {
            sb.append("已上传文件摘要：").append(sessionFileSummary).append("\n");
        }
        if (relevantLongTermMemories != null && !relevantLongTermMemories.isBlank()) {
            sb.append("相关长期记忆：").append(relevantLongTermMemories);
        }
        return sb.toString();
    }

    /**
     * Mark remaining planned steps as skipped.
     */
    private void markStepSkipped(DeepThinkPlan plan, int fromIndex) {
        for (int i = fromIndex; i < plan.getSteps().size(); i++) {
            plan.getSteps().get(i).setStatus("SKIPPED");
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
