package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.intent.application.IntentResolution;

/**
 * 当前 turn 的意图路由结果持有器。
 * <p>
 * SessionFileTools 等工具会读取这里的 IntentResolution，用于限制知识库范围或工具边界。
 */
public final class CurrentIntentResolutionHolder {

    private static final ThreadLocal<IntentResolution> CURRENT_INTENT = new ThreadLocal<>();

    private CurrentIntentResolutionHolder() {
    }

    /**
     * 绑定当前 turn 的意图路由结果。
     * <p>
     * ChatEventProcessor 在创建 ChatAgent 前写入；后续工具解析和 RAG 检索都会读取它，
     * 从而让“本轮应该查哪个知识库/开放哪些工具”的边界贯穿整个 Agent 运行。
     */
    public static void set(IntentResolution intentResolution) {
        CURRENT_INTENT.set(intentResolution);
    }

    /**
     * 读取当前 turn 的意图结果。
     * <p>
     * 允许返回 null：部分测试、旧入口或无意图路由场景会走默认工具/默认知识库范围。
     */
    public static IntentResolution get() {
        return CURRENT_INTENT.get();
    }

    /**
     * 清理当前线程上的意图结果。
     * <p>
     * 如果不清理，线程池复用时下一个 turn 可能误用上一轮的 KB scope 或工具 scope。
     */
    public static void clear() {
        CURRENT_INTENT.remove();
    }
}
