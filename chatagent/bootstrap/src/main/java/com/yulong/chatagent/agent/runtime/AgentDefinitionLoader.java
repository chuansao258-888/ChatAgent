package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.port.AgentRepository;
import org.springframework.stereotype.Component;

/**
 * 从管理端持久化仓储加载 Agent 定义。
 * <p>
 * Agent runtime 不直接依赖 MyBatis/表结构，而是通过 AgentRepository 这个端口读取配置。
 */
@Component
public class AgentDefinitionLoader {

    private final AgentRepository agentRepository;

    public AgentDefinitionLoader(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /**
     * 根据 Agent ID 加载一份运行时定义。
     *
     * @param agentId Agent 配置 ID
     * @return 运行时 Agent 定义
     */
    public AgentDefinition load(String agentId) {
        return new AgentDefinition(agentRepository.findById(agentId));
    }
}
