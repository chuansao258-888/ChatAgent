package com.yulong.chatagent.agent.runtime;

/**
 * 当前 Agent 执行线程绑定的 chatSessionId。
 * <p>
 * 工具回调通过它获取真实会话 ID，而不是让模型把 sessionId 作为工具参数传入，
 * 从而减少内部 ID 泄漏和伪造调用的风险。
 */
public final class CurrentChatSessionHolder {

    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    private CurrentChatSessionHolder() {
    }

    /**
     * 绑定当前执行线程正在处理的会话。
     * <p>
     * ChatAgent.run() 进入主循环前调用；之后同一线程里的工具回调可以通过 require() 读取。
     * 这里保存的是后端可信 sessionId，不从 LLM tool arguments 获取。
     */
    public static void set(String chatSessionId) {
        CURRENT_SESSION_ID.set(chatSessionId);
    }

    /**
     * 宽松读取当前会话 ID。
     * <p>
     * 返回 null 表示当前线程没有绑定会话；适合可选场景。工具执行这种强依赖场景应使用 require()。
     */
    public static String get() {
        return CURRENT_SESSION_ID.get();
    }

    /**
     * 强制读取当前会话 ID。
     * <p>
     * RAG 工具依赖 sessionId 限定会话文件范围，所以没有绑定时直接失败，
     * 比静默返回空结果更容易暴露调用链问题。
     */
    public static String require() {
        // 工具执行必须发生在 ChatAgent.run() 绑定上下文之后；没有绑定说明调用链异常。
        String chatSessionId = CURRENT_SESSION_ID.get();
        if (chatSessionId == null || chatSessionId.isBlank()) {
            throw new IllegalStateException("No active chat session bound to current thread");
        }
        return chatSessionId;
    }

    /**
     * 清理当前线程绑定。
     * <p>
     * 线程池线程会复用，finally 中清理可以避免下一个用户请求读到上一个会话的 sessionId。
     */
    public static void clear() {
        CURRENT_SESSION_ID.remove();
    }
}
