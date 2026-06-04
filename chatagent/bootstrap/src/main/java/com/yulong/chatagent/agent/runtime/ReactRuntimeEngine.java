package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.AgentState;
import com.yulong.chatagent.agent.AgentThinkingEngine;
import com.yulong.chatagent.agent.AgentToolExecutionEngine;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

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

    private AgentState agentState;
    private ChatResponse lastChatResponse;

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
                policy.getMaxToolCallsPerStep()
        );

        this.toolExecutionEngine = new AgentToolExecutionEngine(
                context.availableTools(),
                context.chatOptions(),
                context.turnId(),
                context.messageBridge()
        );
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
        this.lastChatResponse = this.thinkingEngine.think(this.chatMemory, this.chatSessionId);
        org.springframework.util.Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            agentState = AgentState.FINISHED;
            return;
        }

        boolean terminated = this.toolExecutionEngine.execute(
                this.chatMemory,
                this.chatSessionId,
                this.lastChatResponse
        );
        if (terminated) {
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
        try {
            ChatOptions streamOptions = this.chatOptions.copy();
            if (streamOptions instanceof ToolCallingChatOptions toolOptions) {
                toolOptions.setToolCallbacks(List.of());
            }

            List<Message> promptMessages = this.chatMemory.get(this.chatSessionId);
            List<Message> finalPromptMessages = new ArrayList<>(promptMessages.size() + 1);

            Map<String, String> vars = Map.of(
                    "sessionFileSummary", this.sessionFileSummary,
                    "relevantLongTermMemories", this.relevantLongTermMemories
            );
            String finalAnswerPrompt = this.promptLoader.render(PromptConstants.AGENT_FINAL_ANSWER, vars);

            finalPromptMessages.add(new SystemMessage(finalAnswerPrompt));
            finalPromptMessages.addAll(promptMessages);

            Prompt prompt = Prompt.builder()
                    .chatOptions(streamOptions)
                    .messages(finalPromptMessages)
                    .build();

            this.messageBridge.streamFinalResponse(chatSessionId, turnId, prompt, this.llmService, false);
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
}
