package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.AgentTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code agent_template} table.
 */
@Mapper
public interface AgentTemplateMapper {

    int insert(AgentTemplate template);

    List<AgentTemplate> selectAll();

    AgentTemplate selectById(@Param("id") String id);

    AgentTemplate selectByCode(@Param("code") String code);

    int updateById(AgentTemplate template);

    int deleteById(@Param("id") String id);
}

