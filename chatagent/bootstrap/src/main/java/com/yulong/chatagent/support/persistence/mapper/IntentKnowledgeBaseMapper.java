package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.IntentKnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper for intent to knowledge-base bindings.
 */
@Mapper
public interface IntentKnowledgeBaseMapper {

    List<IntentKnowledgeBase> selectByIntentNodeIds(@Param("intentNodeIds") List<String> intentNodeIds);

    int insert(IntentKnowledgeBase binding);

    int batchInsert(@Param("bindings") List<IntentKnowledgeBase> bindings);

    int deleteByIntentNodeIds(@Param("intentNodeIds") List<String> intentNodeIds);
}
