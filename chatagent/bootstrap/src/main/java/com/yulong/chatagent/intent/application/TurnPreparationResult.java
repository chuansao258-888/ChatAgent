package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;

/**
 * ConversationTurnPreparationService 的标准输出。
 *
 * 编排层只需要看这个对象就能决定下一步：
 * 1. directReply 有值：直接把这段话写成 assistant 消息，不进入 AgentRuntime（不携带 contract）；
 * 2. intentResolution 有值：带着意图资源范围进入 AgentRuntime；
 * 3. 两者都为空：按普通聊天 passthrough 处理。
 *
 * Phase 1 起在进入 Agent runtime 的 dispatch 结果上额外携带 TurnExecutionContract，
 * 用于 warn 模式观测。注意：passthrough 结果仍然会被 orchestrator dispatch 进异步
 * Agent runtime，所以 enabled 主链对 passthrough turn 也走带 contract 的 dispatch；
 * passthrough() 这个工厂方法只是 legacy/no-contract 兜底，enabled=true 时一般不直接使用。
 */
public record TurnPreparationResult(
        IntentResolution intentResolution,
        String rewrittenInput,
        String directReply,
        TurnExecutionContract executionContract
) {
    public boolean isDirectReply() {
        // directReply 是最高优先级分支，常见于澄清问题、SYSTEM 意图、或意图树缺失的系统提示。
        return directReply != null && !directReply.isBlank();
    }

    public static TurnPreparationResult dispatch(IntentResolution resolution, String rewrittenInput) {
        // 进入后续 dispatch：Local/MQ dispatcher 会把 rewrittenInput 作为本轮真正交给 Agent 的输入。
        return new TurnPreparationResult(resolution, rewrittenInput, null, null);
    }

    public static TurnPreparationResult dispatch(IntentResolution resolution, String rewrittenInput, TurnExecutionContract executionContract) {
        // 带 contract 的 dispatch：warn 模式下每个进入 Agent runtime 的 turn 都会携带 contract。
        return new TurnPreparationResult(resolution, rewrittenInput, null, executionContract);
    }

    public static TurnPreparationResult direct(String directReply) {
        // 直接回复：当前 turn 在编排层结束，不产生 agent.run MQ 任务。
        return new TurnPreparationResult(null, null, directReply, null);
    }

    public static TurnPreparationResult passthrough() {
        // 没有意图系统参与时使用，让原始 query 继续走普通 AgentRuntime。
        // 注意：passthrough 结果仍然会被 orchestrator dispatch 进异步 Agent runtime，
        // 所以它是"无意图边界但仍进入 Agent"的路径，不是"不进入 Agent"。
        return new TurnPreparationResult(null, null, null, null);
    }
}
