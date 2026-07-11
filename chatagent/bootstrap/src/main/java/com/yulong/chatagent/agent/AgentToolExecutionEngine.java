package com.yulong.chatagent.agent;

import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
/**
 * ReAct 循环里的“行动阶段”封装。
 * <p>
 * 模型只会产出 tool_call 描述；这里负责调用 Spring AI 的工具执行器，
 * 得到 tool_response 后再把完整对话历史写回 Agent 记忆。
 */
public class AgentToolExecutionEngine {

    private final AgentToolExecutionCoordinator coordinator;
    private final ChatOptions chatOptions;
    private final String turnId;
    private final AgentMessageBridge messageBridge;

    public AgentToolExecutionEngine(List<ToolCallback> availableTools,
                             ChatOptions chatOptions,
                             String turnId,
                             AgentMessageBridge messageBridge) {
        this.coordinator = new AgentToolExecutionCoordinator(availableTools);
        this.chatOptions = chatOptions;
        this.turnId = turnId;
        this.messageBridge = messageBridge;
    }

    /**
     * 执行模型请求的工具调用，并把工具响应写回短期记忆。
     *
     * @param chatMemory 当前 Agent 的窗口记忆
     * @param chatSessionId 当前会话 ID
     * @param chatResponse 上一步模型输出
     * @return 如果工具响应里出现 terminate，返回 {@code true} 让外层循环停止
     */
    public boolean execute(ChatMemory chatMemory, String chatSessionId, ChatResponse chatResponse) {
        Assert.notNull(chatResponse, "Last chat client response cannot be null");

        // 没有工具调用时不做任何事；正常情况下外层 step() 已经挡住这个分支。
        if (!chatResponse.hasToolCalls()) {
            return false;
        }

        long startTime = System.nanoTime();
        // ToolCallingManager 需要当前完整历史和 chatOptions，才能把 assistant tool_call
        // 与实际工具回调匹配起来，并生成规范化后的 conversationHistory。
        Prompt prompt = Prompt.builder()
                .messages(chatMemory.get(chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = this.coordinator.execute(prompt, chatResponse);

        // 采用“全量替换”而不是手动 append：Spring AI 返回的 history 已经保证
        // assistant tool_call 与 tool_response 的顺序和配对关系正确。
        chatMemory.clear(chatSessionId);
        chatMemory.add(chatSessionId, toolExecutionResult.conversationHistory());

        // 当前实现约定最后一条就是本轮工具响应消息，后续会逐个响应落库并推给前端。
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "Tool " + resp.name() + " response: " + resp.responseData())
                .collect(Collectors.joining("\n"));

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Tool execution completed: traceId={}, sessionId={}, responses={}, durationMs={}, result={}",
                TraceContext.getTraceId(),
                chatSessionId,
                toolResponseMessage.getResponses().size(),
                durationMs,
                collect);
        this.messageBridge.persistAndPublish(chatSessionId, turnId, toolResponseMessage);

        // terminate 工具目前虽可能被禁用，但保留这个终止协议，方便未来重新开放。
        return toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"));
    }

    /**
     * Execute tool calls without touching ChatMemory. Used by DeepThink which
     * manages its own local conversation history. This is the shared execution
     * path — DeepThink must NOT call {@code ToolCallback.call()} directly.
     *
     * @param assistantMessage the model output containing tool calls
     * @return the tool response message, or null if execution failed
     */
    public ToolResponseMessage executeToolCallsDirect(org.springframework.ai.chat.messages.AssistantMessage assistantMessage) {
        return coordinator.executeDirect(assistantMessage, chatOptions);
    }
}
