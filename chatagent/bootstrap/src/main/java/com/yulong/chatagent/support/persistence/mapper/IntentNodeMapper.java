package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.IntentNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for intent tree nodes.
 */
@Mapper
public interface IntentNodeMapper {

    List<IntentNode> selectByAgentIdAndVersion(String agentId, int version);

    IntentNode selectById(String id);

    int insert(IntentNode intentNode);

    int batchInsert(@Param("intentNodes") List<IntentNode> intentNodes);

    int updateById(IntentNode intentNode);

    int deleteByIds(@Param("ids") List<String> ids);

    List<Integer> selectPublishedVersionsByAgentId(String agentId);

    Integer selectMaxVersionByAgentId(String agentId);
}
