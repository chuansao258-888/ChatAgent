package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.agent.AgentMessageBridge;
import com.yulong.chatagent.agent.DecisionVisibility;
import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.model.SseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DeepThink 步骤执行器——对单个计划步骤运行有界的 ReAct 子循环。
 * <p>
 * 每次迭代使用 {@link DecisionVisibility#INTERNAL_TRACE_ONLY} 调用 LLM，
 * 工具响应通过 {@link AgentMessageBridge#persistInternalToolResponses} 持久化为内部消息。
 * <p>
 * 对话历史跨迭代维护：每次迭代的 assistant tool_call + tool_response 都追加到历史列表中，
 * 确保 LLM 在后续迭代中能看到之前的工具调用结果。
 */
@Slf4j
public class DeepThinkStepExecutor {

    private final AgentMessageBridge messageBridge;
    private final LLMService llmService;
    private final List<ToolCallback> availableTools;
    private final boolean deepThinking;
    private final PromptLoader promptLoader;
    private final com.yulong.chatagent.agent.AgentToolExecutionEngine toolExecutionEngine;

    public DeepThinkStepExecutor(AgentMessageBridge messageBridge,
                                  LLMService llmService,
                                  List<ToolCallback> availableTools,
                                  boolean deepThinking,
                                  PromptLoader promptLoader) {
        this(messageBridge, llmService, availableTools, deepThinking, promptLoader, null);
    }

    public DeepThinkStepExecutor(AgentMessageBridge messageBridge,
                                  LLMService llmService,
                                  List<ToolCallback> availableTools,
                                  boolean deepThinking,
                                  PromptLoader promptLoader,
                                  com.yulong.chatagent.agent.AgentToolExecutionEngine toolExecutionEngine) {
        this.messageBridge = messageBridge;
        this.llmService = llmService;
        this.availableTools = availableTools;
        this.deepThinking = deepThinking;
        this.promptLoader = promptLoader;
        this.toolExecutionEngine = toolExecutionEngine;
    }

    /**
     * 执行单个计划步骤。
     *
     * @param chatSessionId       聊天会话 ID
     * @param turnId              当前轮次 ID
     * @param step                要执行的计划步骤
     * @param maxReactSteps       该步骤的最大 ReAct 迭代次数
     * @param notebook            观察笔记本（累加工具使用记录）
     * @param planGoal            总体计划目标
     * @param observations        已有观察摘要
     * @param maxTotalToolCalls   全局工具调用预算上限（0 = 不限制）
     * @param maxTotalLlmCalls    全局 LLM 调用预算上限（0 = 不限制）
     * @return 步骤结论文本
     */
    public String executeStep(String chatSessionId, String turnId,
                              DeepThinkPlanStep step, int maxReactSteps,
                              DeepThinkNotebook notebook,
                              String planGoal, String observations,
                              int maxTotalToolCalls, int maxTotalLlmCalls) {
        String languageSource = DeepThinkLanguageSupport.stepLanguageSource(step, planGoal);
        boolean preferChinese = DeepThinkLanguageSupport.prefersChinese(languageSource);

        String toolNames = availableTools.stream()
                .map(ToolCallback::getToolDefinition)
                .map(td -> td.name())
                .collect(Collectors.joining(", "));

        // 发送 AI_EXECUTING 状态
        messageBridge.publishStatusEvent(chatSessionId, turnId,
                SseMessage.Type.AI_EXECUTING,
                (preferChinese ? "执行 " : "Executing ") + step.getId() + ": " + truncate(step.getTitle(), 30) + "...");

        String stepSystemPrompt = buildStepSystemPrompt(step, planGoal, observations, toolNames, preferChinese);

        // 维护跨迭代的对话历史，让 LLM 看到之前的工具调用和结果
        List<Message> conversationHistory = new ArrayList<>();
        conversationHistory.add(new SystemMessage(stepSystemPrompt));
        conversationHistory.add(new UserMessage(preferChinese
                ? "请开始执行步骤 " + step.getId() + "。"
                : "Start executing step " + step.getId() + "."));

        // 有界的 ReAct 子循环
        for (int i = 0; i < maxReactSteps; i++) {
            // 预算检查：迭代开始前检查是否已超出全局预算
            if (maxTotalLlmCalls > 0 && notebook.getTotalLlmCalls() >= maxTotalLlmCalls) {
                log.warn("Step {} iteration {} skipped: LLM call budget exhausted ({}>={})",
                        step.getId(), i, notebook.getTotalLlmCalls(), maxTotalLlmCalls);
                break;
            }
            if (maxTotalToolCalls > 0 && notebook.getTotalToolCalls() >= maxTotalToolCalls) {
                log.warn("Step {} iteration {} skipped: tool call budget exhausted ({}>={})",
                        step.getId(), i, notebook.getTotalToolCalls(), maxTotalToolCalls);
                break;
            }

            // 非首次迭代时追加用户提示到对话历史
            if (i > 0) {
                conversationHistory.add(new UserMessage(preferChinese
                        ? "请基于刚才的工具结果继续执行步骤 " + step.getId() + "，或输出结论。"
                        : "Continue executing step " + step.getId() + " from the tool results above, or output the conclusion."));
            }

            BufferedStreamingResponse response = messageBridge.collectDecisionResponse(
                    chatSessionId, turnId,
                    new Prompt(conversationHistory),
                    stepSystemPrompt,
                    availableTools,
                    llmService,
                    DecisionVisibility.INTERNAL_TRACE_ONLY,
                    deepThinking,
                    "EXECUTE",
                    step.getId()
            );

            notebook.incrementLlmCalls();

            if (response == null || response.response() == null) {
                log.warn("Step {} iteration {} received null response", step.getId(), i);
                break;
            }

            ChatResponse chatResponse = response.response();
            AssistantMessage output = chatResponse.getResult().getOutput();

            if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
                // 有工具调用 → 执行工具，持久化内部响应
                List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

                // 截断到剩余预算：防止单次模型返回多个 tool calls 突破全局预算
                if (maxTotalToolCalls > 0) {
                    int remaining = maxTotalToolCalls - notebook.getTotalToolCalls();
                    if (remaining <= 0) {
                        log.warn("Step {} iteration {}: no tool call budget remaining, skipping {} tool calls",
                                step.getId(), i, toolCalls.size());
                        break;
                    }
                    if (toolCalls.size() > remaining) {
                        log.warn("Step {} iteration {}: truncating {} tool calls to {} (remaining budget)",
                                step.getId(), i, toolCalls.size(), remaining);
                        toolCalls = toolCalls.subList(0, remaining);
                        // 重建截断后的 AssistantMessage，确保对话历史一致
                        output = AssistantMessage.builder()
                                .content(output.getText())
                                .toolCalls(toolCalls)
                                .build();
                    }
                }

                log.debug("Step {} iteration {} has {} tool calls", step.getId(), i, toolCalls.size());

                // 将 assistant 的 tool_call 消息追加到对话历史
                conversationHistory.add(output);

                // 执行工具调用
                ToolResponseMessage toolResponse = executeToolCalls(output);
                if (toolResponse != null) {
                    // 只记录实际执行的截断后 tool calls
                    for (var tc : toolCalls) {
                        notebook.recordToolUsage(tc.name(), 1);
                    }
                    // 将工具响应追加到对话历史
                    conversationHistory.add(toolResponse);
                    // 持久化内部工具响应
                    messageBridge.persistInternalToolResponses(
                            chatSessionId, turnId, toolResponse, "EXECUTE", step.getId());
                }

                // 工具调用后检查是否超出工具预算
                if (maxTotalToolCalls > 0 && notebook.getTotalToolCalls() >= maxTotalToolCalls) {
                    log.warn("Step {} tool call budget exhausted after iteration {}", step.getId(), i);
                    break;
                }
            } else {
                // 无工具调用 → 步骤完成，提取结论
                String text = output.getText();
                if (text != null && !text.isBlank()) {
                    return truncate(text, 200);
                }
                break;
            }
        }

        // 如果循环结束还没有明确结论，返回 PARTIAL
        return preferChinese ? "部分完成" : "Partially completed";
    }

    /**
     * 执行工具调用并返回 ToolResponseMessage。
     * Phase 5: uses the shared AgentToolExecutionEngine when available;
     * falls back to direct matching only when no shared engine is provided
     * (backward compatibility for tests that construct the executor directly).
     */
    private ToolResponseMessage executeToolCalls(AssistantMessage assistantMessage) {
        if (toolExecutionEngine != null) {
            return toolExecutionEngine.executeToolCallsDirect(assistantMessage);
        }
        // Legacy fallback for tests that don't inject the shared engine.
        try {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (var toolCall : assistantMessage.getToolCalls()) {
                ToolCallback matchedTool = availableTools.stream()
                        .filter(tc -> tc.getToolDefinition().name().equals(toolCall.name()))
                        .findFirst()
                        .orElse(null);

                if (matchedTool != null) {
                    String result = matchedTool.call(toolCall.arguments());
                    responses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), result));
                } else {
                    log.warn("No matching tool found for: {}", toolCall.name());
                    responses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(),
                            "Error: tool '" + toolCall.name() + "' not found"));
                }
            }
            return ToolResponseMessage.builder()
                    .responses(responses)
                    .build();
        } catch (Exception e) {
            log.warn("Tool execution failed for step: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用 PromptLoader 渲染步骤执行系统提示词。
     */
    private String buildStepSystemPrompt(DeepThinkPlanStep step, String planGoal,
                                          String observations, String toolNames,
                                          boolean preferChinese) {
        String listSeparator = preferChinese ? "；" : "; ";
        Map<String, String> vars = Map.of(
                "stepId", step.getId(),
                "stepTitle", step.getTitle(),
                "stepObjective", step.getObjective(),
                "stepDoneCriteria", step.getDoneCriteria() != null
                        ? String.join(listSeparator, step.getDoneCriteria())
                        : (preferChinese ? "无特定标准" : "No specific criteria"),
                "planGoal", planGoal != null ? planGoal : "",
                "stepExpectedEvidence", step.getExpectedEvidence() != null
                        ? String.join(listSeparator, step.getExpectedEvidence())
                        : (preferChinese ? "无" : "None"),
                "observations", observations != null ? observations : (preferChinese ? "暂无" : "None yet"),
                "availableTools", toolNames
        );
        return promptLoader.render(PromptConstants.DEEPTHINK_STEP_EXECUTOR, vars);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
