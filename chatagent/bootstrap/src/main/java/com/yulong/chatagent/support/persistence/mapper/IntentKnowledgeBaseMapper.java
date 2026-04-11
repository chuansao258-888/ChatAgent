package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.IntentKnowledgeBaseDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for intent to knowledge-base bindings.
 */
@Mapper
public interface IntentKnowledgeBaseMapper {

    List<IntentKnowledgeBaseDTO> selectByIntentNodeIds(@Param("intentNodeIds") List<String> intentNodeIds);

    int insert(IntentKnowledgeBaseDTO binding);

    int batchInsert(@Param("bindings") List<IntentKnowledgeBaseDTO> bindings);

    int deleteByIntentNodeIds(@Param("intentNodeIds") List<String> intentNodeIds);

    int deleteByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);
}
