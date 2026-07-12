package com.yulong.chatagent.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 {@link AgentRunBudget} 的计数与耗尽判定。这是 ARRB Phase 1 run-wide 预算的核心，
 * 保证两个模式在 run 开始时拿到一致的预算快照，并能准确报告最先命中的上限。
 */
class AgentRunBudgetTest {

    private static AgentRunPolicy policy(int proposals, int dispatches, int llm, long elapsedMs) {
        return new AgentRunPolicy(
                10, 4, 0, 0, dispatches, llm, 0, 0,
                true, false, true, true, proposals, elapsedMs);
    }

    @Test
    void consumeToolProposalRespectsLimitAndReportsExhaustion() {
        AgentRunBudget budget = new AgentRunBudget(policy(2, 5, 5, 60_000L), System.nanoTime());

        assertThat(budget.consumeToolProposal()).isTrue();
        assertThat(budget.consumeToolProposal()).isTrue();
        // 第三次超过提案上限：返回 false，计数不再增长。
        assertThat(budget.consumeToolProposal()).isFalse();
        assertThat(budget.getToolProposalsConsumed()).isEqualTo(2);
        assertThat(budget.exhaustedCounter()).isEqualTo("TOOL_PROPOSAL_BUDGET_EXHAUSTED");
    }

    @Test
    void consumeActualDispatchRespectsLimit() {
        AgentRunBudget budget = new AgentRunBudget(policy(10, 1, 5, 60_000L), System.nanoTime());

        assertThat(budget.consumeActualDispatch()).isTrue();
        assertThat(budget.consumeActualDispatch()).isFalse();
        assertThat(budget.getActualDispatchesConsumed()).isEqualTo(1);
        assertThat(budget.exhaustedCounter()).isEqualTo("TOOL_DISPATCH_BUDGET_EXHAUSTED");
    }

    @Test
    void consumeLlmDecisionRespectsLimit() {
        AgentRunBudget budget = new AgentRunBudget(policy(10, 10, 1, 60_000L), System.nanoTime());

        assertThat(budget.consumeLlmDecision()).isTrue();
        assertThat(budget.consumeLlmDecision()).isFalse();
        assertThat(budget.getLlmDecisionsConsumed()).isEqualTo(1);
        assertThat(budget.exhaustedCounter()).isEqualTo("LLM_DECISION_BUDGET_EXHAUSTED");
    }

    @Test
    void elapsedExceededReportsDeadlineExhaustion() {
        // 起始时刻放在很久以前，墙钟预算立即超限。
        long pastStart = System.nanoTime() - 2_000_000_000L; // ~2s ago
        AgentRunBudget budget = new AgentRunBudget(policy(10, 10, 10, 1L), pastStart);

        assertThat(budget.elapsedExceeded()).isTrue();
        assertThat(budget.exhaustedCounter()).isEqualTo("ELAPSED_DEADLINE_EXHAUSTED");
    }

    @Test
    void noExhaustionWhileBudgetRemains() {
        AgentRunBudget budget = new AgentRunBudget(policy(10, 10, 10, 60_000L), System.nanoTime());

        assertThat(budget.consumeToolProposal()).isTrue();
        assertThat(budget.consumeActualDispatch()).isTrue();
        assertThat(budget.consumeLlmDecision()).isTrue();
        assertThat(budget.exhaustedCounter()).isNull();
        assertThat(budget.elapsedExceeded()).isFalse();
    }

    @Test
    void exhaustedCounterReportsFirstHitInPriorityOrder() {
        // 同时命中提案和派发上限时，提案优先被报告（提案是更早的循环保护点）。
        AgentRunBudget budget = new AgentRunBudget(policy(1, 1, 10, 60_000L), System.nanoTime());
        budget.consumeToolProposal();
        budget.consumeActualDispatch();

        assertThat(budget.exhaustedCounter()).isEqualTo("TOOL_PROPOSAL_BUDGET_EXHAUSTED");
    }
}
