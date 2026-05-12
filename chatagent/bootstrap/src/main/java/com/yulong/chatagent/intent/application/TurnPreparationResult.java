package com.yulong.chatagent.intent.application;

/**
 * ConversationTurnPreparationService 的标准输出。
 *
 * 编排层只需要看这个对象就能决定下一步：
 * 1. directReply 有值：直接把这段话写成 assistant 消息，不进入 AgentRuntime；
 * 2. intentResolution 有值：带着意图资源范围进入 AgentRuntime；
 * 3. 两者都为空：按普通聊天 passthrough 处理。
 */
public record TurnPreparationResult(
        IntentResolution intentResolution,
        String rewrittenInput,
        String directReply
) {
    public boolean isDirectReply() {
        // directReply 是最高优先级分支，常见于澄清问题、SYSTEM 意图、或意图树缺失的系统提示。
        return directReply != null && !directReply.isBlank();
    }

    public static TurnPreparationResult dispatch(IntentResolution resolution, String rewrittenInput) {
        // 进入后续 dispatch：Local/MQ dispatcher 会把 rewrittenInput 作为本轮真正交给 Agent 的输入。
        return new TurnPreparationResult(resolution, rewrittenInput, null);
    }

    public static TurnPreparationResult direct(String directReply) {
        // 直接回复：当前 turn 在编排层结束，不产生 agent.run MQ 任务。
        return new TurnPreparationResult(null, null, directReply);
    }

    public static TurnPreparationResult passthrough() {
        // 没有意图系统参与时使用，让原始 query 继续走普通 AgentRuntime。
        return new TurnPreparationResult(null, null, null);
    }
}
