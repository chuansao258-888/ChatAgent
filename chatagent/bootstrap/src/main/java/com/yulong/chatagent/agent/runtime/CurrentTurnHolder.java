package com.yulong.chatagent.agent.runtime;

/**
 * 当前 Agent 执行线程绑定的 turnId。
 * <p>
 * turnId 用来把用户消息、assistant 消息、tool_response、SSE 和引用来源都关联到同一轮对话。
 */
public final class CurrentTurnHolder {

    private static final ThreadLocal<String> CURRENT_TURN_ID = new ThreadLocal<>();

    private CurrentTurnHolder() {
    }

    /**
     * 绑定当前 Agent 运行所属的 turnId。
     * <p>
     * turnId 是“同一个 session 里的第几轮/哪一轮”的业务归属标识，
     * 工具响应、最终回答、SSE 事件和 citations 都靠它归并到同一轮。
     */
    public static void set(String turnId) {
        CURRENT_TURN_ID.set(turnId);
    }

    /**
     * 宽松读取当前 turnId。
     * <p>
     * 返回 null 表示当前线程尚未进入一次明确的对话 turn。
     */
    public static String get() {
        return CURRENT_TURN_ID.get();
    }

    /**
     * 强制读取当前 turnId。
     * <p>
     * 工具执行必须知道 turnId，否则工具结果和引用来源无法安全挂到最终 assistant 消息。
     */
    public static String require() {
        // 工具调用必须知道当前 turn，否则后续 citations 和 tool_response 无法正确归属。
        String turnId = CURRENT_TURN_ID.get();
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalStateException("No active turn bound to current thread");
        }
        return turnId;
    }

    /**
     * 清理当前线程绑定，防止线程复用导致 turnId 串线。
     */
    public static void clear() {
        CURRENT_TURN_ID.remove();
    }
}
