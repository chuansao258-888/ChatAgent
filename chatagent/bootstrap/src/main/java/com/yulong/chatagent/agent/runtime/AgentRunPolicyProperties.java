package com.yulong.chatagent.agent.runtime;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行策略配置。
 * <p>
 * 将原来硬编码在 ChatAgent 里的循环上限、工具调用限制等提取到配置文件，
 * 不同运行模式（ReAct / DeepThink）可以拥有各自的预算。
 * <p>
 * ARRB Phase 1 为两种模式都增加了 run-wide 预算：工具提案数、实际派发数、
 * LLM 决策数与墙钟上限。零或负数不代表"无限"——非法配置会在启动时直接失败
 * （见 {@link #validate()}），避免运行期悄悄退化。
 */
@Slf4j
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

        /**
         * ARRB Phase 1：run-wide 模型工具提案上限。提案在 preflight 之前消耗，
         * 因此改错误的 name/arguments 不能制造免费循环。
         */
        private int maxTotalToolProposals = 40;

        /**
         * ARRB Phase 1：run-wide 实际派发（回调执行）上限。
         */
        private int maxTotalToolCalls = 20;

        /**
         * ARRB Phase 1：run-wide 逻辑模型请求上限（决策/最终/修复/兜底都计）。
         */
        private int maxTotalLlmCalls = 24;

        /**
         * ARRB Phase 1：run-wide 墙钟上限（毫秒）。
         */
        private long maxElapsedMs = 300_000L;
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

        /**
         * ARRB Phase 1：run-wide 模型工具提案上限。
         */
        private int maxTotalToolProposals = 30;

        /**
         * ARRB Phase 1：run-wide 墙钟上限（毫秒）。
         */
        private long maxElapsedMs = 300_000L;
    }

    /**
     * 启动期校验：零或负数不代表无限，非法预算直接让应用启动失败。
     */
    @PostConstruct
    void validate() {
        ReactPolicy r = react;
        requirePositive("react.maxSteps", r.getMaxSteps());
        requirePositive("react.maxToolCallsPerStep", r.getMaxToolCallsPerStep());
        requirePositive("react.maxTotalToolProposals", r.getMaxTotalToolProposals());
        requirePositive("react.maxTotalToolCalls", r.getMaxTotalToolCalls());
        requirePositive("react.maxTotalLlmCalls", r.getMaxTotalLlmCalls());
        requirePositive("react.maxElapsedMs", r.getMaxElapsedMs());

        DeepThinkPolicy d = deepthink;
        requirePositive("deepthink.maxPlanItems", d.getMaxPlanItems());
        requirePositive("deepthink.maxReactStepsPerPlanItem", d.getMaxReactStepsPerPlanItem());
        requirePositive("deepthink.maxReflectionRounds", d.getMaxReflectionRounds());
        requirePositive("deepthink.maxVerificationRounds", d.getMaxVerificationRounds());
        requirePositive("deepthink.maxTotalToolCalls", d.getMaxTotalToolCalls());
        requirePositive("deepthink.maxTotalLlmCalls", d.getMaxTotalLlmCalls());
        requirePositive("deepthink.maxTotalToolProposals", d.getMaxTotalToolProposals());
        requirePositive("deepthink.maxElapsedMs", d.getMaxElapsedMs());

        log.info("Agent runtime budgets validated: react=[steps={}, toolCallsPerStep={}, "
                        + "toolProposals={}, toolCalls={}, llmCalls={}, elapsedMs={}], "
                        + "deepthink=[planItems={}, toolCalls={}, llmCalls={}, toolProposals={}, elapsedMs={}]",
                r.getMaxSteps(), r.getMaxToolCallsPerStep(), r.getMaxTotalToolProposals(),
                r.getMaxTotalToolCalls(), r.getMaxTotalLlmCalls(), r.getMaxElapsedMs(),
                d.getMaxPlanItems(), d.getMaxTotalToolCalls(), d.getMaxTotalLlmCalls(),
                d.getMaxTotalToolProposals(), d.getMaxElapsedMs());
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "chatagent.agent.runtime " + name + " must be > 0 but was " + value);
        }
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0L) {
            throw new IllegalStateException(
                    "chatagent.agent.runtime " + name + " must be > 0 but was " + value);
        }
    }
}
