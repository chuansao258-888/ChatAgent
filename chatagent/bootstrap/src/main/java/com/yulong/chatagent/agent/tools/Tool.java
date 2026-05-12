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
}
