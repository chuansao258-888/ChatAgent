package com.yulong.chatagent.agent.runtime;

/**
 * 一次 Agent run 的可变预算计数器，贯穿 ReAct / DeepThink 的思考与工具路径。
 * <p>
 * 由拥有该 run 的运行时引擎在 run 开始时创建一次，按 run-wide 上限从 {@link AgentRunPolicy}
 * 快照初始化。四个独立计数器分别表示：
 * <ul>
 *   <li>{@code toolProposals}：模型提出的原始工具调用数（在 preflight 校验之前就消耗，
 *       这样改错误的 name/arguments 无法制造免费循环）；</li>
 *   <li>{@code actualDispatches}：真正派发给回调的工具调用数；</li>
 *   <li>{@code llmDecisions}：每次逻辑模型请求消耗一次（决策/规划/步骤/反思/验证/修复/兜底/最终）；</li>
 *   <li>{@code elapsedMs}：run 的墙钟预算上限。</li>
 * </ul>
 * 所有 {@code consume...} 方法返回剩余预算是否仍然非负，调用方据此决定是 PARTIAL/BLOCKED 还是继续。
 * <p>
 * 该类只做计数与判定，不持有 Spring、LLM 或工具回调引用，便于在引擎与测试中直接构造。
 */
public final class AgentRunBudget {

    private final int maxToolProposals;
    private final int maxActualDispatches;
    private final int maxLlmDecisions;
    private final long maxElapsedMs;
    private final long startNanos;

    private int toolProposalsConsumed;
    private int actualDispatchesConsumed;
    private int llmDecisionsConsumed;
    private final java.util.Set<String> proposalFingerprints = new java.util.HashSet<>();

    /**
     * 从一个 run-wide 策略快照与 run 起始时刻构造预算。
     *
     * @param policy        一次 run 的策略快照
     * @param startNanos    run 起始的 {@code System.nanoTime()}，用于计算墙钟预算
     */
    public AgentRunBudget(AgentRunPolicy policy, long startNanos) {
        // 0/负数表示该策略未设置 run-wide 预算（如旧版两参构造或回退路径），
        // 此时视为"不限制"，避免把未设置预算误判成立即耗尽。
        this.maxToolProposals = policy.getMaxTotalToolProposals() > 0
                ? policy.getMaxTotalToolProposals() : Integer.MAX_VALUE;
        this.maxActualDispatches = policy.getMaxTotalToolCalls() > 0
                ? policy.getMaxTotalToolCalls() : Integer.MAX_VALUE;
        this.maxLlmDecisions = policy.getMaxTotalLlmCalls() > 0
                ? policy.getMaxTotalLlmCalls() : Integer.MAX_VALUE;
        this.maxElapsedMs = policy.getMaxElapsedMs() > 0
                ? policy.getMaxElapsedMs() : Long.MAX_VALUE;
        this.startNanos = startNanos;
    }

    /** 在 preflight 之前消耗一次工具提案预算；返回是否仍在预算内。 */
    public synchronized boolean consumeToolProposal() {
        if (elapsedExceeded()) {
            return false;
        }
        if (toolProposalsConsumed >= maxToolProposals) {
            return false;
        }
        toolProposalsConsumed++;
        return toolProposalsConsumed <= maxToolProposals;
    }

    /** 真正派发回调前消耗一次实际派发预算；返回是否仍在预算内。 */
    public synchronized boolean consumeActualDispatch() {
        if (elapsedExceeded()) {
            return false;
        }
        if (actualDispatchesConsumed >= maxActualDispatches) {
            return false;
        }
        actualDispatchesConsumed++;
        return actualDispatchesConsumed <= maxActualDispatches;
    }

    /** 每次逻辑模型请求前消耗一次 LLM 决策预算；返回是否仍在预算内。 */
    public synchronized boolean consumeLlmDecision() {
        if (elapsedExceeded()) {
            return false;
        }
        if (llmDecisionsConsumed >= maxLlmDecisions) {
            return false;
        }
        llmDecisionsConsumed++;
        return llmDecisionsConsumed <= maxLlmDecisions;
    }

    /** 当前墙钟是否已超过 run 的 elapsed 上限。 */
    public synchronized boolean elapsedExceeded() {
        return maxElapsedMs == Long.MAX_VALUE ? false : elapsedMs() > maxElapsedMs;
    }

    /** 当前已用墙钟毫秒数。 */
    public synchronized long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public synchronized long remainingElapsedMs() {
        if (maxElapsedMs == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, maxElapsedMs - elapsedMs());
    }

    public synchronized int getToolProposalsConsumed() {
        return toolProposalsConsumed;
    }

    public synchronized int getActualDispatchesConsumed() {
        return actualDispatchesConsumed;
    }

    public synchronized int getLlmDecisionsConsumed() {
        return llmDecisionsConsumed;
    }

    /** Returns false when the exact proposal fingerprint already appeared in this run. */
    public synchronized boolean recordProposalFingerprint(String fingerprint) {
        return fingerprint != null && proposalFingerprints.add(fingerprint);
    }

    public int getMaxToolProposals() {
        return maxToolProposals;
    }

    public int getMaxActualDispatches() {
        return maxActualDispatches;
    }

    public int getMaxLlmDecisions() {
        return maxLlmDecisions;
    }

    public long getMaxElapsedMs() {
        return maxElapsedMs;
    }

    /**
     * 当前已耗尽、最先命中的上限名；若仍有余量返回 {@code null}。
     * 引擎据此把结果落成准确的 stop reason（ARRB-AC-007）。
     */
    public synchronized String exhaustedCounter() {
        if (toolProposalsConsumed >= maxToolProposals) {
            return "TOOL_PROPOSAL_BUDGET_EXHAUSTED";
        }
        if (actualDispatchesConsumed >= maxActualDispatches) {
            return "TOOL_DISPATCH_BUDGET_EXHAUSTED";
        }
        if (llmDecisionsConsumed >= maxLlmDecisions) {
            return "LLM_DECISION_BUDGET_EXHAUSTED";
        }
        if (elapsedMs() > maxElapsedMs) {
            return "ELAPSED_DEADLINE_EXHAUSTED";
        }
        return null;
    }
}
