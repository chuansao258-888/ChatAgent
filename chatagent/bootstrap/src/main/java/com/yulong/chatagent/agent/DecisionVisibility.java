package com.yulong.chatagent.agent;

/**
 * 决策可见性：控制 {@link AgentMessageBridge} 如何处理一次 LLM 决策调用的持久化和 SSE 行为。
 */
public enum DecisionVisibility {
    /**
     * 当前行为：创建 provisional assistant 消息、流式推给前端。
     * 如果模型返回 tool calls，删除 provisional 消息并发布 TURN_ROLLBACK。
     * 如果没有 tool calls，这条消息就是最终回答。
     */
    USER_VISIBLE_PROVISIONAL,

    /**
     * DeepThink 内部调用：不创建 provisional 消息、不流式推给前端。
     * tool_call/tool_response 持久化为 {@code metadata.internal=true} 消息。
     * 非 tool_call 的响应不持久化（调用方从 {@code BufferedStreamingResponse} 获取）。
     * 可选发送 status-only SSE 事件（AI_PLANNING / AI_EXECUTING / AI_THINKING）。
     */
    INTERNAL_TRACE_ONLY
}
