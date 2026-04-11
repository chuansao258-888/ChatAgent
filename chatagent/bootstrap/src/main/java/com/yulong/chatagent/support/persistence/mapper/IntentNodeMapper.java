package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for intent tree nodes.
 */
@Mapper
public interface IntentNodeMapper {

    List<IntentNodeDTO> selectByAgentIdAndVersion(String agentId, int version);

    IntentNodeDTO selectById(String id);

    int insert(IntentNodeDTO intentNode);

    int batchInsert(@Param("intentNodes") List<IntentNodeDTO> intentNodes);

    int updateById(IntentNodeDTO intentNode);

    int deleteByIds(@Param("ids") List<String> ids);

    List<Integer> selectPublishedVersionsByAgentId(String agentId);

    Integer selectMaxVersionByAgentId(String agentId);
}
