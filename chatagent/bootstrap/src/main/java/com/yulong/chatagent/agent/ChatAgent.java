package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
/**
 * 单次会话轮次里的有状态 Agent 运行实例。
 * <p>
 * 每次用户消息都会创建一个新的 {@code ChatAgent}，它独占本轮的短期记忆、
 * 工具回调列表和 ReAct 执行循环。这样可以把运行态数据限制在当前 turn 内，
 * 避免多个请求之间共享可变状态。
 */
public class ChatAgent {
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    private static final int RUNTIME_MEMORY_SLACK = 4;

    private String agentId;
    private String name;
    private String description;
    private String systemPrompt;
    private PromptLoader promptLoader;
    private LLMService llmService;
    private AgentState agentState;
    private List<ToolCallback> availableTools;
    private String sessionFileSummary;
    private String sessionSummary;
    private String userProfileSummary;
    private String turnId;
    private ChatMemory chatMemory;
    private String chatSessionId;
    private ChatOptions chatOptions;
    private AgentMessageBridge messageBridge;
    private ChatResponse lastChatResponse;
    private AgentThinkingEngine thinkingEngine;
    private AgentToolExecutionEngine toolExecutionEngine;

    public ChatAgent() {
    }

    public ChatAgent(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     PromptLoader promptLoader,
                     LLMService llmService,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     String sessionFileSummary,
                     String sessionSummary,
                     String userProfileSummary,
                     String userId,
                     String turnId,
                     String chatSessionId,
                     AgentMessageBridge messageBridge) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.promptLoader = promptLoader;
        this.llmService = llmService;
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.sessionFileSummary = StringUtils.hasText(sessionFileSummary)
                ? sessionFileSummary
                : promptLoader.load(PromptConstants.FALLBACK_SESSION_FILES);
        this.sessionSummary = StringUtils.hasText(sessionSummary)
                ? sessionSummary
                : promptLoader.load(PromptConstants.FALLBACK_SESSION_SUMMARY);
        this.userProfileSummary = StringUtils.hasText(userProfileSummary)
                ? userProfileSummary
                : promptLoader.load(PromptConstants.FALLBACK_USER_PROFILE);
        this.turnId = turnId;
        this.chatSessionId = chatSessionId;
        this.messageBridge = messageBridge;
        this.agentState = AgentState.IDLE;

        // L1 记忆窗口需要同时容纳系统提示词、历史消息，以及本轮运行时新增的
        // assistant/tool_response 消息；RUNTIME_MEMORY_SLACK 是给 ReAct 中间消息预留的缓冲。
        List<Message> initialMemory = memory == null ? List.of() : memory;
        int configuredMaxMessages = maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
        int requiredCapacity = initialMemory.size() + RUNTIME_MEMORY_SLACK + (StringUtils.hasLength(systemPrompt) ? 1 : 0);

        // 使用 Spring AI 的窗口记忆承载本轮上下文。历史消息已经由 AgentMemoryLoader
        // 按 token 预算裁剪过，这里主要负责运行期追加和窗口容量兜底。
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(Math.max(configuredMaxMessages, requiredCapacity))
                .build();
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }
        this.chatMemory.add(chatSessionId, initialMemory);

        // 关闭 Spring AI 自动执行工具：模型只负责“提出工具调用”，真正的执行由
        // AgentToolExecutionEngine 显式控制，方便持久化 tool_call/tool_response 并插入审计逻辑。
        ToolCallingChatOptions toolCallingChatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolContext(buildToolContext(userId, chatSessionId, turnId))
                .build();
        this.chatOptions = toolCallingChatOptions;
        this.thinkingEngine = new AgentThinkingEngine(
                this.promptLoader,
                this.llmService,
                this.chatOptions,
                this.availableTools,
                this.sessionFileSummary,
                this.userProfileSummary,
                this.turnId,
                this.messageBridge
        );
        this.toolExecutionEngine = new AgentToolExecutionEngine(
                this.availableTools,
                this.chatOptions,
                this.turnId,
                this.messageBridge
        );
    }

    private Map<String, Object> buildToolContext(String userId, String chatSessionId, String turnId) {
        // toolContext 是 Spring AI 传给工具执行层的后端上下文，不会作为工具参数暴露给模型。
        // 这里放 user/session/turn，方便部分工具在不信任 LLM 输入的情况下拿到真实运行上下文。
        Map<String, Object> context = new LinkedHashMap<>();
        if (StringUtils.hasText(userId)) {
            context.put("userId", userId);
        }
        if (StringUtils.hasText(chatSessionId)) {
            context.put("sessionId", chatSessionId);
        }
        if (StringUtils.hasText(turnId)) {
            context.put("turnId", turnId);
        }
        return context;
    }

    /**
     * 执行一次 ReAct 迭代。
     * <p>
     * 单步只有两个阶段：先让模型“想一步”，如果没有工具调用就说明已经给出最终答案；
     * 如果返回了 tool calls，就执行工具、写回记忆，然后下一轮继续让模型基于工具结果推理。
     */
    private void step() {
        this.lastChatResponse = this.thinkingEngine.think(this.chatMemory, this.chatSessionId);
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        // ReAct 的分叉点：没有 tool_call 就是最终回答，有 tool_call 就进入 Acting 阶段。
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
            log.info("Task finished");
        }
    }

    /**
     * 运行 Agent，直到模型给出最终答案、工具显式终止、异常退出，或达到最大步数保护。
     */
    public AgentRunResult run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        long startTime = System.nanoTime();
        int executedSteps = 0;
        log.info("Agent run started: traceId={}, agentId={}, sessionId={}",
                TraceContext.getTraceId(), agentId, chatSessionId);

        try {
            // 工具通过 ThreadLocal 获取 session/turn 等后端上下文。进入循环前绑定，finally 中必须清理。
            CurrentTurnKnowledgeHitHolder.reset();
            CurrentChatSessionHolder.set(this.chatSessionId);
            CurrentTurnHolder.set(this.turnId);
            // MAX_STEPS 是防循环保险：模型如果反复要求工具调用，也会在固定步数后停止。
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                step();
                executedSteps = currentStep;
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
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
        } finally {
            // Servlet/MQ 线程会被复用，ThreadLocal 不清理会把上一个用户的上下文泄漏给下一个请求。
            CurrentTurnKnowledgeHitHolder.clear();
            CurrentIntentResolutionHolder.clear();
            CurrentTurnHolder.clear();
            CurrentChatSessionHolder.clear();
        }
    }

    @Override
    public String toString() {
        return "ChatAgent {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
