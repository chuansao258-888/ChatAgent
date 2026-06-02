package com.yulong.chatagent.agent.runtime;

/**
 * Agent 运行策略——从配置属性构建的不可变策略快照。
 * <p>
 * 运行时引擎通过此类读取步数上限、工具调用预算等，而不是直接依赖配置 bean。
 * 这样可以在一次 run 内使用固定的策略快照，不受中途配置刷新影响。
 */
public class AgentRunPolicy {

    private final int maxSteps;
    private final int maxToolCallsPerStep;

    // DeepThink-specific fields (default 0 for ReAct mode)
    private final int maxPlanItems;
    private final int maxReactStepsPerPlanItem;
    private final int maxTotalToolCalls;
    private final int maxTotalLlmCalls;
    private final int maxReflectionRounds;
    private final int maxVerificationRounds;
    private final boolean planningModelDeepThinking;
    private final boolean executionModelDeepThinking;
    private final boolean reflectionModelDeepThinking;
    private final boolean verificationModelDeepThinking;

    public AgentRunPolicy(int maxSteps, int maxToolCallsPerStep) {
        this(maxSteps, maxToolCallsPerStep, 0, 0, 0, 0, 0, 0);
    }

    public AgentRunPolicy(int maxSteps, int maxToolCallsPerStep,
                   int maxPlanItems, int maxReactStepsPerPlanItem,
                   int maxTotalToolCalls, int maxTotalLlmCalls) {
        this(maxSteps, maxToolCallsPerStep, maxPlanItems, maxReactStepsPerPlanItem,
                maxTotalToolCalls, maxTotalLlmCalls, 0, 0);
    }

    public AgentRunPolicy(int maxSteps, int maxToolCallsPerStep,
                          int maxPlanItems, int maxReactStepsPerPlanItem,
                          int maxTotalToolCalls, int maxTotalLlmCalls,
                          int maxReflectionRounds, int maxVerificationRounds) {
        this(maxSteps, maxToolCallsPerStep, maxPlanItems, maxReactStepsPerPlanItem,
                maxTotalToolCalls, maxTotalLlmCalls, maxReflectionRounds, maxVerificationRounds,
                true, false, true, true);
    }

    public AgentRunPolicy(int maxSteps, int maxToolCallsPerStep,
                          int maxPlanItems, int maxReactStepsPerPlanItem,
                          int maxTotalToolCalls, int maxTotalLlmCalls,
                          int maxReflectionRounds, int maxVerificationRounds,
                          boolean planningModelDeepThinking,
                          boolean executionModelDeepThinking,
                          boolean reflectionModelDeepThinking,
                          boolean verificationModelDeepThinking) {
        this.maxSteps = maxSteps;
        this.maxToolCallsPerStep = maxToolCallsPerStep;
        this.maxPlanItems = maxPlanItems;
        this.maxReactStepsPerPlanItem = maxReactStepsPerPlanItem;
        this.maxTotalToolCalls = maxTotalToolCalls;
        this.maxTotalLlmCalls = maxTotalLlmCalls;
        this.maxReflectionRounds = maxReflectionRounds;
        this.maxVerificationRounds = maxVerificationRounds;
        this.planningModelDeepThinking = planningModelDeepThinking;
        this.executionModelDeepThinking = executionModelDeepThinking;
        this.reflectionModelDeepThinking = reflectionModelDeepThinking;
        this.verificationModelDeepThinking = verificationModelDeepThinking;
    }

    /**
     * 从配置属性构建标准 ReAct 策略。
     */
    public static AgentRunPolicy react(AgentRunPolicyProperties properties) {
        return new AgentRunPolicy(
                properties.getReact().getMaxSteps(),
                properties.getReact().getMaxToolCallsPerStep()
        );
    }

    /**
     * 从配置属性构建 DeepThink 策略，包含计划/执行预算限制。
     */
    public static AgentRunPolicy deepthink(AgentRunPolicyProperties properties) {
        AgentRunPolicyProperties.DeepThinkPolicy dt = properties.getDeepthink();
        return new AgentRunPolicy(
                properties.getReact().getMaxSteps(),
                properties.getReact().getMaxToolCallsPerStep(),
                dt.getMaxPlanItems(),
                dt.getMaxReactStepsPerPlanItem(),
                dt.getMaxTotalToolCalls(),
                dt.getMaxTotalLlmCalls(),
                dt.getMaxReflectionRounds(),
                dt.getMaxVerificationRounds(),
                dt.isPlanningModelDeepThinking(),
                dt.isExecutionModelDeepThinking(),
                dt.isReflectionModelDeepThinking(),
                dt.isVerificationModelDeepThinking()
        );
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getMaxToolCallsPerStep() {
        return maxToolCallsPerStep;
    }

    public int getMaxPlanItems() {
        return maxPlanItems;
    }

    public int getMaxReactStepsPerPlanItem() {
        return maxReactStepsPerPlanItem;
    }

    public int getMaxTotalToolCalls() {
        return maxTotalToolCalls;
    }

    public int getMaxTotalLlmCalls() {
        return maxTotalLlmCalls;
    }

    public int getMaxReflectionRounds() {
        return maxReflectionRounds;
    }

    public int getMaxVerificationRounds() {
        return maxVerificationRounds;
    }

    public boolean isPlanningModelDeepThinking() {
        return planningModelDeepThinking;
    }

    public boolean isExecutionModelDeepThinking() {
        return executionModelDeepThinking;
    }

    public boolean isReflectionModelDeepThinking() {
        return reflectionModelDeepThinking;
    }

    public boolean isVerificationModelDeepThinking() {
        return verificationModelDeepThinking;
    }
}
