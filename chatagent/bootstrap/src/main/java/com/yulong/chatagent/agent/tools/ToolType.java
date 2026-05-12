package com.yulong.chatagent.agent.tools;

/**
 * 工具可用性类型。
 * <p>
 * FIXED 工具默认加入运行时工具集合；OPTIONAL 工具需要 Agent 配置或意图结果允许后才加入。
 */
public enum ToolType {
    FIXED,
    OPTIONAL
}
