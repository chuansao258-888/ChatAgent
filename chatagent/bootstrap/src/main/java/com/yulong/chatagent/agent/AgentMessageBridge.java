package com.yulong.chatagent.agent;

import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.support.dto.AgentTraceMetadata;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Agent 消息桥接接口。
 * <p>
 * Agent runtime 只产出 Spring AI Message；桥接层负责把这些消息转成数据库记录和 SSE 事件，
 * 让核心 ReAct 循环不直接依赖会话展示层。
 */
public interface AgentMessageBridge {
    /**
     * 持久化一条运行时消息，并推送给前端订阅端。
     *
     * @param chatSessionId 会话 ID
     * @param turnId 当前 turn ID
     * @param message Agent 产出的 assistant/tool 消息
     */
    void persistAndPublish(String chatSessionId, String turnId, Message message);

    /**
     * 执行最终回答的流式输出。
     *
     * @param chatSessionId 会话 ID
     * @param turnId 当前 turn ID
     * @param prompt 已清空工具的最终回答 Prompt
     * @param llmService LLM 路由服务
     * @param deepThinking 是否使用深度思考模型路由
     * @return 完整最终文本
     */
    String streamFinalResponse(String chatSessionId, String turnId, Prompt prompt, LLMService llmService, boolean deepThinking);

    /**
     * 执行带工具的决策流，并把内容先作为临时 assistant 消息推给前端。
     * <p>
     * 如果模型最终产出 tool calls，临时消息会被删除并通过 TURN_ROLLBACK 通知前端撤回；
     * 如果没有 tool calls，这次流式输出就直接成为最终回答。
     *
     * @param chatSessionId 会话 ID
     * @param turnId 当前 turn ID
     * @param prompt 决策 Prompt
     * @param systemPrompt 路由时附加的决策系统提示词
     * @param tools 本轮可用工具
     * @param llmService LLM 路由服务
     * @return 缓冲后的流式响应，供 Agent loop 判断是否进入工具执行分支
     */
    BufferedStreamingResponse streamDecisionResponse(String chatSessionId,
                                                    String turnId,
                                                    Prompt prompt,
                                                    String systemPrompt,
                                                    List<ToolCallback> tools,
                                                    LLMService llmService);

    // 当前生产流程不会调用缓冲响应回放：
    // streamDecisionResponse 已经在”无 tool calls”时直接落库并推送最终消息；
    // “有 tool calls”时会回滚临时消息并进入工具执行，后续由 streamFinalResponse 重新生成最终回答。
    // void publishBufferedFinalResponse(String chatSessionId, String turnId, BufferedStreamingResponse bufferedResponse);

    /**
     * 内部决策收集：根据 visibility 决定持久化和 SSE 行为。
     *
     * <ul>
     *   <li>{@link DecisionVisibility#USER_VISIBLE_PROVISIONAL}：委托给 {@link #streamDecisionResponse}。</li>
     *   <li>{@link DecisionVisibility#INTERNAL_TRACE_ONLY}：不创建 provisional 消息、不流式推前端。
     *       tool_call/tool_response 持久化为 {@code metadata.internal=true} 消息。
     *       非 tool_call 的响应不持久化（调用方从返回值获取）。</li>
     * </ul>
     *
     * @param chatSessionId 会话 ID
     * @param turnId        当前 turn ID
     * @param prompt        决策 Prompt
     * @param systemPrompt  路由时附加的决策系统提示词
     * @param tools         本轮可用工具
     * @param llmService    LLM 路由服务
     * @param visibility    决策可见性
     * @param deepThinking  是否使用深度思考模型路由
     * @param deepThinkPhase DeepThink 阶段标签（”PLAN” / “EXECUTE” / “REFLECT” / “VERIFY”），写入 metadata
     * @param planStepId    计划步骤 ID（如 “S2”），写入 metadata，可为 null
     * @return 缓冲后的流式响应
     */
    BufferedStreamingResponse collectDecisionResponse(String chatSessionId,
                                                      String turnId,
                                                      Prompt prompt,
                                                      String systemPrompt,
                                                      List<ToolCallback> tools,
                                                      LLMService llmService,
                                                      DecisionVisibility visibility,
                                                      boolean deepThinking,
                                                      String deepThinkPhase,
                                                      String planStepId);

    /**
     * 发送 status-only SSE 事件（AI_PLANNING / AI_EXECUTING / AI_THINKING）。
     * payload 只有 statusText + turnId，没有 message 和 metadata。
     *
     * @param chatSessionId 会话 ID
     * @param turnId        当前 turn ID
     * @param type          SSE 事件类型（仅限 AI_PLANNING / AI_EXECUTING / AI_THINKING）
     * @param statusText    状态文本，如 “正在规划...” / “正在执行 S2/5...”
     */
    void publishStatusEvent(String chatSessionId, String turnId, SseMessage.Type type, String statusText);

    /**
     * 持久化 internal tool response 消息，不推送 SSE。
     * DeepThink 引擎执行工具后调用此方法记录工具响应，与 {@link #collectDecisionResponse} 中
     * 自动持久化的 internal assistant tool_call 消息配对。
     *
     * @param chatSessionId       会话 ID
     * @param turnId              当前 turn ID
     * @param toolResponseMessage 工具响应消息
     * @param deepThinkPhase      DeepThink 阶段标签
     * @param planStepId          计划步骤 ID，可为 null
     */
    void persistInternalToolResponses(String chatSessionId, String turnId,
                                       ToolResponseMessage toolResponseMessage,
                                       String deepThinkPhase, String planStepId);

    /**
     * 附加 DeepThink 追踪元数据到最终 assistant 消息。
     * 查找指定 turn 最新的非 internal assistant 消息，更新其 metadata.agentTrace。
     *
     * @param chatSessionId 会话 ID
     * @param turnId        当前 turn ID
     * @param trace         追踪元数据
     */
    void attachTraceMetadata(String chatSessionId, String turnId, AgentTraceMetadata trace);
}
