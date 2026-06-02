package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.AgentRunResult;

/**
 * Agent 运行时引擎接口。
 * <p>
 * ChatAgent 作为 per-turn 门面，内部委托给具体的运行时引擎执行 ReAct 或 DeepThink 循环。
 * 这样可以把不同运行模式的循环逻辑隔离到各自的实现类中。
 */
public interface AgentRuntimeEngine {

    /**
     * 执行运行时循环，返回本次运行的结果摘要。
     *
     * @param context 运行时上下文（记忆、工具、策略等）
     * @return 运行结果（成功/失败、耗时、知识命中）
     */
    AgentRunResult run(AgentRunContext context);
}
