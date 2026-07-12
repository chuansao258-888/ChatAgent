package com.yulong.chatagent.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 {@link AgentRunPolicyProperties} 的默认值与启动期校验。ARRB Phase 1 要求：
 * 零或负数的 run-wide 预算不代表"无限"，非法配置必须在启动期失败。
 */
class AgentRunPolicyPropertiesTest {

    @Test
    void defaultsMatchPlanBudgetTable() {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();

        AgentRunPolicyProperties.ReactPolicy react = properties.getReact();
        assertThat(react.getMaxSteps()).isEqualTo(20);
        assertThat(react.getMaxToolCallsPerStep()).isEqualTo(4);
        assertThat(react.getMaxTotalToolProposals()).isEqualTo(40);
        assertThat(react.getMaxTotalToolCalls()).isEqualTo(20);
        assertThat(react.getMaxTotalLlmCalls()).isEqualTo(24);
        assertThat(react.getMaxElapsedMs()).isEqualTo(300_000L);

        AgentRunPolicyProperties.DeepThinkPolicy dt = properties.getDeepthink();
        assertThat(dt.getMaxTotalToolProposals()).isEqualTo(30);
        assertThat(dt.getMaxTotalToolCalls()).isEqualTo(20);
        assertThat(dt.getMaxTotalLlmCalls()).isEqualTo(30);
        assertThat(dt.getMaxElapsedMs()).isEqualTo(300_000L);
    }

    @Test
    void validatePasssWithDefaults() {
        // 默认值都是正数，校验应通过（不抛异常）。
        new AgentRunPolicyProperties().validate();
    }

    @Test
    void validateRejectsZeroReactBudget() {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getReact().setMaxTotalToolProposals(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("react.maxTotalToolProposals")
                .hasMessageContaining("must be > 0");
    }

    @Test
    void validateRejectsNegativeDeepThinkElapsedMs() {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getDeepthink().setMaxElapsedMs(-1L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepthink.maxElapsedMs");
    }

    @Test
    void reactFactorySnapshotsRunWideBudgets() {
        // react() 工厂应把 run-wide 预算从属性快照到策略，而不是丢回 0。
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getReact().setMaxTotalToolProposals(7);
        properties.getReact().setMaxElapsedMs(99_000L);

        AgentRunPolicy policy = AgentRunPolicy.react(properties);

        assertThat(policy.getMaxTotalToolProposals()).isEqualTo(7);
        assertThat(policy.getMaxElapsedMs()).isEqualTo(99_000L);
    }

    @Test
    void deepthinkFactorySnapshotsRunWideBudgets() {
        AgentRunPolicyProperties properties = new AgentRunPolicyProperties();
        properties.getDeepthink().setMaxTotalToolProposals(12);
        properties.getDeepthink().setMaxElapsedMs(123_000L);

        AgentRunPolicy policy = AgentRunPolicy.deepthink(properties);

        assertThat(policy.getMaxTotalToolProposals()).isEqualTo(12);
        assertThat(policy.getMaxElapsedMs()).isEqualTo(123_000L);
    }
}
