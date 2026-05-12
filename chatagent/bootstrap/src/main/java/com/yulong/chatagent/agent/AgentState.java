package com.yulong.chatagent.agent;

/**
 * Agent 运行状态。
 * <p>
 * 当前主循环主要使用 IDLE / FINISHED / ERROR；中间态预留给后续更细粒度的观测或调试。
 */
public enum AgentState {
    IDLE,
    PLANNING,
    THINKING,
    EXECUTING,
    FINISHED,
    ERROR
}
