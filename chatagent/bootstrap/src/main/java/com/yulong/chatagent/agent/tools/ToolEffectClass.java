package com.yulong.chatagent.agent.tools;

/**
 * 执行级别的工具副作用分类（ARRB-DEC-017）。
 * <p>
 * 它是 <em>call-level</em> 效果策略，独立于 intent-level 的 {@code ActionRisk}：
 * coordinator 据此决定一个工具调用是否需要精确用户确认、能否自动重试、以及
 * 崩溃窗口里如何归类副作用。
 * <ul>
 *   <li>{@link #READ_ONLY}：无副作用，可在预算内自动派发与一次有限重试；</li>
 *   <li>{@link #IDEMPOTENT}：有副作用但幂等，遵循其声明的策略，可使用一次有限重试；</li>
 *   <li>{@link #NON_IDEMPOTENT}：有副作用且非幂等，首次提案不派发，需精确确认；不自动重试；</li>
 *   <li>{@link #UNKNOWN}：副作用未知（MCP 默认值），按最保守的 NON_IDEMPOTENT 路径处理。</li>
 * </ul>
 * Built-in {@link Tool} 实现显式声明该值；MCP 在管理员策略（Phase 3）存在前默认 {@link #UNKNOWN}。
 */
public enum ToolEffectClass {
    READ_ONLY,
    IDEMPOTENT,
    NON_IDEMPOTENT,
    UNKNOWN
}
