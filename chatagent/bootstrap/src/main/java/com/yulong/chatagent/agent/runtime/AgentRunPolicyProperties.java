package com.yulong.chatagent.agent.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行策略配置。
 * <p>
 * 将原来硬编码在 ChatAgent 里的循环上限、工具调用限制等提取到配置文件，
 * 不同运行模式（ReAct / DeepThink）可以拥有各自的预算。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chatagent.agent.runtime")
public class AgentRunPolicyProperties {

    /**
     * 默认运行模式：REACT 或 DEEPTHINK。
     */
    private AgentExecutionMode defaultMode = AgentExecutionMode.REACT;

    /**
     * 模式选择方式：USER_EXPLICIT（用户手动选择）。
     * AUTO 预留给未来版本，不在第一版使用。
     */
    private String modeSelection = "USER_EXPLICIT";

    private ReactPolicy react = new ReactPolicy();

    private DeepThinkPolicy deepthink = new DeepThinkPolicy();

    @Data
    public static class ReactPolicy {
        /**
         * ReAct 最大步数，防循环保险。
         */
        private int maxSteps = 20;

        /**
         * 每步最大工具调用数。
         */
        private int maxToolCallsPerStep = 4;
    }

    @Data
    public static class DeepThinkPolicy {
        private boolean enabled = true;

        /**
         * 最大计划步骤数。
         */
        private int maxPlanItems = 5;

        /**
         * 每个计划步骤的最大 ReAct 循环数。
         */
        private int maxReactStepsPerPlanItem = 3;

        /**
         * 最大反思轮次。
         */
        private int maxReflectionRounds = 2;

        /**
         * 最大验证轮次。
         */
        private int maxVerificationRounds = 1;

        /**
         * DeepThink 全局工具调用上限。
         */
        private int maxTotalToolCalls = 20;

        /**
         * DeepThink 全局 LLM 调用上限（仅计 think() 调用，工具执行不计）。
         */
        private int maxTotalLlmCalls = 30;

        /**
         * 规划阶段是否启用 deep thinking 模型。
         */
        private boolean planningModelDeepThinking = true;

        /**
         * 执行阶段是否启用 deep thinking 模型。
         */
        private boolean executionModelDeepThinking = false;

        /**
         * 反思阶段是否启用 deep thinking 模型。
         */
        private boolean reflectionModelDeepThinking = true;

        /**
         * 验证阶段是否启用 deep thinking 模型。
         */
        private boolean verificationModelDeepThinking = true;
    }
}
