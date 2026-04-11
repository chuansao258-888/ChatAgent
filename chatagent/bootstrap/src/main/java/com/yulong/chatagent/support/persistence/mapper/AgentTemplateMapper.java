package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.AssistantTemplateDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code agent_template} table.
 */
@Mapper
public interface AgentTemplateMapper {

    int insert(AssistantTemplateDTO template);

    List<AssistantTemplateDTO> selectAll();

    AssistantTemplateDTO selectById(@Param("id") String id);

    AssistantTemplateDTO selectByCode(@Param("code") String code);

    int updateById(AssistantTemplateDTO template);

    int deleteById(@Param("id") String id);
}
