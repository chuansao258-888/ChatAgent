package com.yulong.chatagent.agent.tools;

/**
 * 所有可暴露给 Agent 的工具通用契约。
 * <p>
 * ToolFacadeService 会按 ToolType 区分固定工具和可选工具，AgentToolCallbackFactory
 * 再把这些工具转换成 Spring AI 的 ToolCallback。
 */
public interface Tool {
    /**
     * 返回工具唯一名称，用于 Agent 配置、意图工具范围和运行时注册。
     *
     * @return 工具名称
     */
    String getName();

    /**
     * 返回工具能力描述，主要用于管理端展示。
     *
     * @return 工具描述
     */
    String getDescription();

    /**
     * 返回工具类型，决定它是否默认附加给 Agent。
     *
     * @return 工具类型
     */
    ToolType getType();

    /**
     * 返回该工具的执行级别副作用分类（ARRB-DEC-017）。
     * <p>
     * Built-in 工具应显式覆盖此方法以声明真实的副作用语义；默认 {@link ToolEffectClass#UNKNOWN}
     * 保证 MCP 与未声明工具按最保守路径处理，不会在无证据时被当成只读自动派发。
     *
     * @return 工具副作用分类，默认 {@link ToolEffectClass#UNKNOWN}
     */
    default ToolEffectClass effectClass() {
        return ToolEffectClass.UNKNOWN;
    }

    /**
     * 声明 owned adapter 是否能在当前线程上执行 coordinator 提供的剩余截止时间。
     * 未显式声明的工具（尤其 MCP）必须 fail closed。
     */
    default DeadlineMode deadlineMode() {
        return DeadlineMode.UNSUPPORTED;
    }
}
