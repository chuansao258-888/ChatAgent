package com.yulong.chatagent.agent.tools;

/**
 * 工具回调是否执行 run 剩余截止时间的声明（ARRB-DEC-018）。
 * <p>
 * 不要把同步回调包进盲目超时线程池：当前 retrieval/citation holder 是线程绑定的。
 * 因此 coordinator 只在回调声明 {@link #ENFORCED} 时才认为它可派发，并把剩余截止时间
 * 通过 {@code ChatOptions.toolContext} 传给回调；声明 {@link #UNSUPPORTED} 的回调
 * 在调用前 fail-closed，避免无界调用。
 * <ul>
 *   <li>{@link #ENFORCED}：owned adapter 自身会执行剩余 run 截止时间（取其配置超时与
 *       剩余截止时间的较小值），coordinator 可安全派发；</li>
 *   <li>{@link #UNSUPPORTED}：回调无法执行截止时间，coordinator 不派发并以
 *       {@code TOOL_DEADLINE_UNSUPPORTED} 失败。</li>
 * </ul>
 */
public enum DeadlineMode {
    ENFORCED,
    UNSUPPORTED
}
