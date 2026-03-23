package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;

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
    private ChatClient chatClient;
    private AgentState agentState;
    private List<ToolCallback> availableTools;
    private String sessionFileSummary;
    private String userProfileSummary;
    private ToolCallingManager toolCallingManager;
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
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     String sessionFileSummary,
                     String userProfileSummary,
                     String chatSessionId,
                     AgentMessageBridge messageBridge) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.sessionFileSummary = StringUtils.hasText(sessionFileSummary)
                ? sessionFileSummary
                : "No attached session files available";
        this.userProfileSummary = StringUtils.hasText(userProfileSummary)
                ? userProfileSummary
                : "No persistent user profile available";
        this.chatSessionId = chatSessionId;
        this.messageBridge = messageBridge;
        this.agentState = AgentState.IDLE;

        List<Message> initialMemory = memory == null ? List.of() : memory;
        int configuredMaxMessages = maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
        int requiredCapacity = initialMemory.size() + RUNTIME_MEMORY_SLACK + (StringUtils.hasLength(systemPrompt) ? 1 : 0);

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(Math.max(configuredMaxMessages, requiredCapacity))
                .build();
        this.chatMemory.add(chatSessionId, initialMemory);

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.thinkingEngine = new AgentThinkingEngine(
                this.chatClient,
                this.chatOptions,
                this.availableTools,
                this.sessionFileSummary,
                this.userProfileSummary,
                this.messageBridge
        );
        this.toolExecutionEngine = new AgentToolExecutionEngine(
                this.toolCallingManager,
                this.chatOptions,
                this.messageBridge
        );
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
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        long startTime = System.nanoTime();
        int executedSteps = 0;
        log.info("Agent run started: traceId={}, agentId={}, sessionId={}",
                TraceContext.getTraceId(), agentId, chatSessionId);

        try {
            CurrentChatSessionHolder.set(this.chatSessionId);
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
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.error("Error running agent: traceId={}, agentId={}, sessionId={}, steps={}, durationMs={}",
                    TraceContext.getTraceId(), agentId, chatSessionId, executedSteps, durationMs, e);
            throw new RuntimeException("Error running agent", e);
        } finally {
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
