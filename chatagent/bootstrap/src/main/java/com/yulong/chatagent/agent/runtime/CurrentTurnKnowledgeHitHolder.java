package com.yulong.chatagent.agent.runtime;

/**
 * 当前 turn 是否命中知识检索的 ThreadLocal 状态。
 * <p>
 * RAG 工具执行时写入，AgentRunResult 读取后上报给 Dashboard；
 * 没有尝试检索时默认视为 true，避免“非知识类问题”被误算成知识未命中。
 */
public final class CurrentTurnKnowledgeHitHolder {

    private static final ThreadLocal<KnowledgeState> CURRENT_STATE = new ThreadLocal<>();

    private CurrentTurnKnowledgeHitHolder() {
    }

    /**
     * 开始一次 Agent run 前重置知识命中状态。
     * <p>
     * 这里必须 set 一个新对象，而不是只 remove：后续 recordRetrievalResult 可以在同一线程内累加本轮多次检索结果。
     */
    public static void reset() {
        CURRENT_STATE.set(new KnowledgeState());
    }

    /**
     * 记录一次 RAG 检索是否命中。
     * <p>
     * 一个 turn 里模型可能多次调用 SessionFileSearchTool；只要任意一次有结果，本轮就算 knowledgeHit=true。
     * 这个指标用于运行结果和 Dashboard，不参与模型回答生成。
     */
    public static void recordRetrievalResult(boolean hit) {
        // 一个 turn 内可能多次检索，只要有一次命中就认为本轮知识命中。
        KnowledgeState state = CURRENT_STATE.get();
        if (state == null) {
            state = new KnowledgeState();
            CURRENT_STATE.set(state);
        }
        state.retrievalAttempted = true;
        state.anyHit = state.anyHit || hit;
    }

    /**
     * 返回当前 turn 的知识命中状态。
     * <p>
     * 设计上“没有尝试检索”返回 true，因为普通聊天或非 KB 工具任务不应该被统计成知识库 miss。
     * 只有“确实尝试了检索但没有任何结果”才返回 false。
     */
    public static boolean isKnowledgeHit() {
        KnowledgeState state = CURRENT_STATE.get();
        if (state == null || !state.retrievalAttempted) {
            // 未执行检索的聊天/工具类任务不应该被计为知识库 miss。
            return true;
        }
        return state.anyHit;
    }

    /**
     * 清理 ThreadLocal，避免线程池复用时指标串到下一次 Agent run。
     */
    public static void clear() {
        CURRENT_STATE.remove();
    }

    /**
     * 当前 turn 内的知识命中聚合状态。
     * <p>
     * retrievalAttempted 区分“没查过”和“查过但没命中”；anyHit 用 OR 语义聚合多次检索。
     */
    private static final class KnowledgeState {
        private boolean retrievalAttempted;
        private boolean anyHit;
    }
}
