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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DeepThink 规划阶段——根据用户问题生成结构化执行计划。
 * <p>
 * 调用 LLM 生成 strict JSON plan，通过 {@link DeepThinkJsonParser} 解析。
 * 内部调用使用 {@link DecisionVisibility#INTERNAL_TRACE_ONLY}，不向用户流式输出。
 */
@Slf4j
public class DeepThinkPlanner {

    private final AgentMessageBridge messageBridge;
    private final LLMService llmService;
    private final List<ToolCallback> availableTools;
    private final boolean deepThinking;
    private final PromptLoader promptLoader;

    public DeepThinkPlanner(AgentMessageBridge messageBridge,
                            LLMService llmService,
                            List<ToolCallback> availableTools,
                            boolean deepThinking,
                            PromptLoader promptLoader) {
        this.messageBridge = messageBridge;
        this.llmService = llmService;
        this.availableTools = availableTools;
        this.deepThinking = deepThinking;
        this.promptLoader = promptLoader;
    }

    /**
     * 生成执行计划。
     *
     * @param chatSessionId  聊天会话 ID
     * @param turnId         当前轮次 ID
     * @param userQuestion   用户问题
     * @param sessionContext 会话上下文（文件摘要、相关长期记忆等）
     * @param maxPlanItems   最大步骤数
     * @return 解析后的计划，如果 LLM 输出无法解析则返回 null
     */
    public DeepThinkPlan plan(String chatSessionId, String turnId,
                              String userQuestion, String sessionContext,
                              int maxPlanItems) {
        boolean preferChinese = DeepThinkLanguageSupport.prefersChinese(userQuestion);
        // 发送 AI_PLANNING 状态事件
        messageBridge.publishStatusEvent(chatSessionId, turnId,
                SseMessage.Type.AI_PLANNING, preferChinese ? "正在规划..." : "Planning...");

        // 构建可用工具名称列表
        String toolNames = availableTools.stream()
                .map(ToolCallback::getToolDefinition)
                .map(td -> td.name())
                .collect(Collectors.joining(", "));

        // 使用 PromptLoader 渲染 planner 提示词
        Map<String, String> vars = Map.of(
                "maxPlanItems", String.valueOf(maxPlanItems),
                "availableTools", toolNames,
                "userQuestion", userQuestion != null ? userQuestion : "",
                "sessionContext", sessionContext != null ? sessionContext : ""
        );
        String systemPrompt = promptLoader.render(PromptConstants.DEEPTHINK_PLANNER, vars);
        String userPrompt = preferChinese
                ? "请根据以上信息生成执行计划。"
                : "Generate the execution plan from the information above.";

        // 内部调用 LLM，不暴露给用户
        BufferedStreamingResponse response = messageBridge.collectDecisionResponse(
                chatSessionId, turnId,
                new Prompt(List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(systemPrompt),
                        new org.springframework.ai.chat.messages.UserMessage(userPrompt)
                )),
                systemPrompt,
                List.of(), // planner 不需要工具
                llmService,
                DecisionVisibility.INTERNAL_TRACE_ONLY,
                deepThinking,
                "PLAN",
                null
        );

        if (response == null || response.response() == null) {
            log.warn("DeepThink planner received null response from LLM");
            return null;
        }

        ChatResponse chatResponse = response.response();
        String content = chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText()
                : null;

        if (content == null || content.isBlank()) {
            log.warn("DeepThink planner received empty content from LLM");
            return null;
        }

        return DeepThinkJsonParser.parsePlan(content, maxPlanItems);
    }
}
