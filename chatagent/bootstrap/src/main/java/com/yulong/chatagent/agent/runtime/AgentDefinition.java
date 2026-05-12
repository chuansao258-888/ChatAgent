package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.support.dto.AgentDTO;

/**
 * 运行时使用的 Agent 定义快照。
 * <p>
 * 当前只包了一层持久化 AgentDTO，保留这个 record 是为了让后续运行时定义扩展
 * 不直接污染数据库 DTO。
 *
 * @param config 持久化 Agent 配置
 */
public record AgentDefinition(AgentDTO config) {
}
