package com.yulong.chatagent.agent;

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
 * Stateful runtime agent that alternates between LLM reasoning and tool execution.
 * <p>
 * One {@code ChatAgent} instance is created per chat run and owns the memory,
 * tool callbacks, and execution loop for a single chat session.
 */
public class ChatAgent {
    private static final Integer MAX_STEPS = 20;
    private static final Integer DEFAULT_MAX_MESSAGES = 20;
    private static final int RUNTIME_MEMORY_SLACK = 4;

    private String agentId;
    private String name;
    private String description;
    private String systemPrompt;
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
        this.llmService = llmService;
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.sessionFileSummary = StringUtils.hasText(sessionFileSummary)
                ? sessionFileSummary
                : "No attached session files available";
        this.sessionSummary = StringUtils.hasText(sessionSummary)
                ? sessionSummary
                : "No historical context summary available";
        this.userProfileSummary = StringUtils.hasText(userProfileSummary)
                ? userProfileSummary
                : "No persistent user profile available";
        this.turnId = turnId;
        this.chatSessionId = chatSessionId;
        this.messageBridge = messageBridge;
        this.agentState = AgentState.IDLE;

        List<Message> initialMemory = memory == null ? List.of() : memory;
        int configuredMaxMessages = maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
        int requiredCapacity = initialMemory.size() + RUNTIME_MEMORY_SLACK + (StringUtils.hasLength(systemPrompt) ? 1 : 0);

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(Math.max(configuredMaxMessages, requiredCapacity))
                .build();
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }
        this.chatMemory.add(chatSessionId, initialMemory);

        ToolCallingChatOptions toolCallingChatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolContext(buildToolContext(userId, chatSessionId, turnId))
                .build();
        this.chatOptions = toolCallingChatOptions;
        this.thinkingEngine = new AgentThinkingEngine(
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
     * Executes one agent loop iteration.
     * <p>
     * A step first asks the model to decide the next action. If no tool call is
     * returned, the run is considered finished. Otherwise tool responses are
     * executed, persisted, and fed back into memory for the next iteration.
     */
    private void step() {
        this.lastChatResponse = this.thinkingEngine.think(this.chatMemory, this.chatSessionId);
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

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
     * Runs the agent until it finishes, errors, or reaches the configured step cap.
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
            CurrentTurnKnowledgeHitHolder.reset();
            CurrentChatSessionHolder.set(this.chatSessionId);
            CurrentTurnHolder.set(this.turnId);
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
