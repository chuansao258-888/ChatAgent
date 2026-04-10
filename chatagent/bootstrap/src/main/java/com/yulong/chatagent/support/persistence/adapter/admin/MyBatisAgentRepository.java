package com.yulong.chatagent.support.persistence.adapter.admin;

import com.yulong.chatagent.agent.port.AgentRepository;
import com.yulong.chatagent.admin.converter.AgentConverter;
import com.yulong.chatagent.support.persistence.entity.Agent;
import com.yulong.chatagent.support.persistence.mapper.AgentMapper;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-backed implementation of the agent repository port.
 */
@Repository
public class MyBatisAgentRepository implements AgentRepository {

    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;

    public MyBatisAgentRepository(AgentMapper agentMapper,
                                  AgentConverter agentConverter) {
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
    }

    @Override
    public List<AgentDTO> findByUserId(String userId) {
        return toDTOList(agentMapper.selectByUserId(userId));
    }

    @Override
    public AgentDTO findById(String id) {
        return toDTO(agentMapper.selectById(id));
    }

    @Override
    public boolean save(AgentDTO agent) {
        Agent entity = toEntity(agent);
        boolean saved = agentMapper.insert(entity) > 0;
        if (saved) {
            agent.setId(entity.getId());
        }
        return saved;
    }

    @Override
    public boolean update(AgentDTO agent) {
        return agentMapper.updateById(toEntity(agent)) > 0;
    }

    @Override
    public boolean deleteById(String id) {
        return agentMapper.deleteById(id) > 0;
    }

    /**
     * Converts persistence entities to DTOs while preserving list ordering.
     */
    private List<AgentDTO> toDTOList(List<Agent> entities) {
        List<AgentDTO> result = new ArrayList<>();
        for (Agent entity : entities) {
            result.add(toDTO(entity));
        }
        return result;
    }

    /**
     * Converts one persistence entity to a DTO and wraps serialization failures.
     */
    private AgentDTO toDTO(Agent entity) {
        if (entity == null) {
            return null;
        }
        try {
            return agentConverter.toDTO(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize agent", e);
        }
    }

    /**
     * Converts one DTO to a persistence entity and wraps serialization failures.
     */
    private Agent toEntity(AgentDTO dto) {
        try {
            return agentConverter.toEntity(dto);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize agent", e);
        }
    }
}
