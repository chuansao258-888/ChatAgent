package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.AgentState;
import com.yulong.chatagent.agent.AgentThinkingEngine;
import com.yulong.chatagent.agent.AgentToolExecutionEngine;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.tools.ToolDispatchContext;
import com.yulong.chatagent.agent.tools.ToolExecutionDescriptorResolver;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 标准 ReAct 运行时引擎。
 * <p>
 * 从 ChatAgent.run() 提取的 ReAct 循环逻辑：
 * Reason (think) -> Act (tool call) -> Observe (tool response) -> Answer (final)。
 * <p>
 * 每次用户消息由 ChatAgent 创建一个新的 ReactRuntimeEngine 实例，
 * 保证运行态数据限制在当前 turn 内。
 */
@Slf4j
public class ReactRuntimeEngine implements AgentRuntimeEngine {

    private final AgentRunPolicy policy;
    private final AgentThinkingEngine thinkingEngine;
    private final AgentToolExecutionEngine toolExecutionEngine;
    private final AgentMessageBridge messageBridge;
    private final ChatMemory chatMemory;
    private final String chatSessionId;
    private final String agentId;
    private final ChatOptions chatOptions;
    private final PromptLoader promptLoader;
    private final LLMService llmService;
    private final String sessionFileSummary;
    private final String relevantLongTermMemories;
    private final String turnId;
    // ARRB Phase 1（F-5）：run-wide 预算与 descriptor 解析器，在每次逻辑模型请求/派发处消耗。
    private final AgentRunBudget budget;
    private final com.yulong.chatagent.agent.tools.ToolExecutionDescriptorResolver descriptorResolver;

    private AgentState agentState;
    private AgentMessageBridge.FinalStreamResult finalStreamResult;
    private ChatResponse lastChatResponse;
    private AgentRunResult.Status stopStatus;
    private String stopReason;

    public ReactRuntimeEngine(AgentRunContext context) {
        this.policy = context.policy();
        this.chatMemory = context.chatMemory();
        this.chatSessionId = context.chatSessionId();
        this.agentId = context.agentId();
        this.chatOptions = context.chatOptions();
        this.promptLoader = context.promptLoader();
        this.llmService = context.llmService();
        this.sessionFileSummary = context.sessionFileSummary();
        this.relevantLongTermMemories = context.relevantLongTermMemories();
        this.turnId = context.turnId();
        this.messageBridge = context.messageBridge();
        this.agentState = AgentState.IDLE;

        this.thinkingEngine = new AgentThinkingEngine(
                context.promptLoader(),
                context.llmService(),
                context.chatOptions(),
                context.availableTools(),
                context.sessionFileSummary(),
                context.relevantLongTermMemories(),
                context.turnId(),
                context.messageBridge(),
                policy.getMaxToolCallsPerStep(),
                context.executionContract()
        );

        this.toolExecutionEngine = new AgentToolExecutionEngine(
                context.availableTools(),
                context.chatOptions(),
                context.turnId(),
                context.messageBridge(),
                policy.getMaxToolCallsPerStep()
        );

        // ARRB Phase 1（cross-review F-1/F-2/F-4/F-5）：构造 run-wide budget、descriptor 解析器、
        // 派发上下文，并注入 tool engine。budget 在每次逻辑模型请求/工具派发处消耗（见 run/step）。
        this.budget = new AgentRunBudget(policy, System.nanoTime());
        this.descriptorResolver = new ToolExecutionDescriptorResolver(context.availableTools());
        var approvedProposal = context.executionContract() != null && context.executionContract().tools() != null
                ? context.executionContract().tools().approvedProposal() : null;
        if (context.approvalPort() != null && context.ledgerPort() != null) {
            this.toolExecutionEngine.configureDispatchContext(new ToolDispatchContext(
                         context.chatSessionId(),
                         context.turnId(),
                         context.approvalPort(),
                         context.ledgerPort(),
                         this.budget,
                         this.descriptorResolver,
                         approvedProposal,
                         "agent-runtime-v1",
                         context.executionContract() == null ? null : context.executionContract().version()));
        }
    }

    @Override
    public AgentRunResult run(AgentRunContext context) {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        long startTime = System.nanoTime();
        int executedSteps = 0;
        int maxSteps = policy.getMaxSteps();
        log.info("Agent run started: traceId={}, agentId={}, sessionId={}, maxSteps={}",
                TraceContext.getTraceId(), agentId, chatSessionId, maxSteps);

        try {
            if (context.executionContract() != null && context.executionContract().tools() != null
                    && context.executionContract().tools().approvedProposal() != null) {
                replayApprovedProposal(context.executionContract().tools().approvedProposal());
                if (stopStatus != null) {
                    return stopResult(startTime);
                }
            }
            for (int i = 0; i < maxSteps && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                step();
                executedSteps = currentStep;

                if (currentStep >= maxSteps && agentState != AgentState.FINISHED) {
                    log.warn("Max steps reached ({}), forcing final synthesis", maxSteps);
                    forceFinalSynthesis();
                }
            }
            agentState = AgentState.FINISHED;

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("Agent run finished: traceId={}, agentId={}, sessionId={}, steps={}, durationMs={}",
                    TraceContext.getTraceId(), agentId, chatSessionId, executedSteps, durationMs);
            if (stopStatus == AgentRunResult.Status.BLOCKED) {
                return AgentRunResult.blocked(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), stopReason);
            }
            if (stopStatus == AgentRunResult.Status.PARTIAL) {
                return AgentRunResult.partial(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), stopReason);
            }
            if (finalStreamResult != null && !finalStreamResult.complete()) {
                return AgentRunResult.partial(durationMs,
                        CurrentTurnKnowledgeHitHolder.isKnowledgeHit(),
                        "FINAL_STREAM_" + finalStreamResult.status().name());
            }
            return AgentRunResult.success(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit());
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.error("Error running agent: traceId={}, agentId={}, sessionId={}, steps={}, durationMs={}",
                    TraceContext.getTraceId(), agentId, chatSessionId, executedSteps, durationMs, e);
            throw new AgentRunException(
                    "Error running agent",
                    e,
                    AgentRunResult.failure(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
            );
        }
    }

    /**
     * 执行一次 ReAct 迭代。
     * <p>
     * 单步只有两个阶段：先让模型"想一步"，如果没有工具调用就说明已经给出最终答案；
     * 如果返回了 tool calls，就执行工具、写回记忆，然后下一轮继续让模型基于工具结果推理。
     * <p>
     * tool calls 数量上限由 {@link AgentThinkingEngine} 在持久化前截断，
     * 保证 DB 中 assistant tool_calls 与后续 tool_response 严格配对。
     */
    private void step() {
        // ARRB Phase 1（F-5/AC-007）：每次逻辑模型请求（决策 think）消耗一次 LLM 预算。
        if (!budget.consumeLlmDecision()) {
            String reason = budget.exhaustedCounter();
            log.warn("Agent run stopped by budget: reason={}", reason == null ? "LLM_DECISION_BUDGET_EXHAUSTED" : reason);
            agentState = AgentState.FINISHED;
            stopStatus = AgentRunResult.Status.PARTIAL;
            stopReason = reason == null ? "LLM_DECISION_BUDGET_EXHAUSTED" : reason;
            return;
        }
        this.lastChatResponse = this.thinkingEngine.think(this.chatMemory, this.chatSessionId);
        org.springframework.util.Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            AgentMessageBridge.FinalStreamResult streamResult =
                    this.thinkingEngine.getLastFinalStreamResult();
            if (streamResult != null && !streamResult.complete()) {
                stopStatus = AgentRunResult.Status.PARTIAL;
                stopReason = "FINAL_STREAM_" + streamResult.status().name();
            }
            agentState = AgentState.FINISHED;
            return;
        }

        AgentToolExecutionEngine.ExecutionOutcome outcome = this.toolExecutionEngine.executeWithOutcome(
                this.chatMemory,
                this.chatSessionId,
                this.lastChatResponse
        );
        if (outcome.blocked()) {
            this.agentState = AgentState.FINISHED;
            this.stopStatus = AgentRunResult.Status.BLOCKED;
            this.stopReason = outcome.stopReason();
        } else if (outcome.partial()) {
            this.agentState = AgentState.FINISHED;
            this.stopStatus = AgentRunResult.Status.PARTIAL;
            this.stopReason = outcome.stopReason();
        } else if (outcome.terminated()) {
            this.agentState = AgentState.FINISHED;
            log.info("Task finished via terminate tool");
        }
    }

    /**
     * 达到最大步数时的强制最终综合。
     * <p>
     * 用已收集到的观察结果生成一个有边界的最终回答，包含不确定性说明。
     * 不再调用任何工具，清空工具列表，直接通过 messageBridge 流式输出最终答案。
     * <p>
     * 如果最终回答流式输出失败，抛出 {@link AgentRunException} 而非静默返回 SUCCESS，
     * 保证 "max-step runs still produce a user-visible final message" 的验收条件。
     */
    private void forceFinalSynthesis() {
        log.info("Forcing final synthesis after max steps reached");
        if (!budget.consumeLlmDecision()) {
            stopStatus = AgentRunResult.Status.PARTIAL;
            stopReason = budget.exhaustedCounter() == null
                    ? "LLM_DECISION_BUDGET_EXHAUSTED" : budget.exhaustedCounter();
            agentState = AgentState.FINISHED;
            return;
        }
        try {
            ChatOptions streamOptions = this.chatOptions.copy();
            if (streamOptions instanceof ToolCallingChatOptions toolOptions) {
                toolOptions.setToolCallbacks(List.of());
            }

            List<Message> promptMessages = this.chatMemory.get(this.chatSessionId);
            List<Message> finalPromptMessages = new ArrayList<>(promptMessages.size() + 1);

            Map<String, String> vars = Map.of(
                    "sessionFileSummary", this.sessionFileSummary,
                    "relevantLongTermMemories", this.relevantLongTermMemories,
                    "latestUserRequest", latestUserRequest(promptMessages)
            );
            String finalAnswerPrompt = this.promptLoader.render(PromptConstants.AGENT_FINAL_ANSWER, vars);

            finalPromptMessages.add(new SystemMessage(finalAnswerPrompt));
            finalPromptMessages.addAll(promptMessages);

            Prompt prompt = Prompt.builder()
                    .chatOptions(streamOptions)
                    .messages(finalPromptMessages)
                    .build();

            this.finalStreamResult = this.messageBridge.streamFinalResponseWithOutcome(
                    chatSessionId, turnId, prompt, this.llmService, false);
            if (this.finalStreamResult == null) {
                this.finalStreamResult = new AgentMessageBridge.FinalStreamResult(
                        AgentMessageBridge.FinalStreamStatus.COMPLETE,
                        this.messageBridge.streamFinalResponse(
                                chatSessionId, turnId, prompt, this.llmService, false));
            }
        } catch (Exception e) {
            log.error("Failed to force final synthesis after max steps reached", e);
            throw new AgentRunException(
                    "Failed to produce final answer after max steps reached",
                    e,
                    AgentRunResult.failure(0, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), e)
            );
        }
        agentState = AgentState.FINISHED;
    }

    private void replayApprovedProposal(com.yulong.chatagent.agent.runtime.contract.ApprovedToolProposal approved) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        approved.approvalId(), "function", approved.toolName(),
                        approved.canonicalArguments())))
                .build();
        chatMemory.add(chatSessionId, assistant);
        messageBridge.persistAndPublish(chatSessionId, turnId, assistant);
        ChatResponse response = new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(assistant)));
        AgentToolExecutionEngine.ExecutionOutcome outcome =
                toolExecutionEngine.executeWithOutcome(chatMemory, chatSessionId, response);
        if (outcome.blocked()) {
            stopStatus = AgentRunResult.Status.BLOCKED;
            stopReason = outcome.stopReason();
            agentState = AgentState.FINISHED;
        } else if (outcome.partial()) {
            stopStatus = AgentRunResult.Status.PARTIAL;
            stopReason = outcome.stopReason();
            agentState = AgentState.FINISHED;
        }
    }

    private AgentRunResult stopResult(long startTime) {
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        return stopStatus == AgentRunResult.Status.BLOCKED
                ? AgentRunResult.blocked(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), stopReason)
                : AgentRunResult.partial(durationMs, CurrentTurnKnowledgeHitHolder.isKnowledgeHit(), stopReason);
    }

    private String latestUserRequest(List<Message> promptMessages) {
        for (int i = promptMessages.size() - 1; i >= 0; i--) {
            if (promptMessages.get(i) instanceof UserMessage userMessage
                    && StringUtils.hasText(userMessage.getText())) {
                return userMessage.getText();
            }
        }
        return "No user-role message is present in the current conversation history.";
    }
}
