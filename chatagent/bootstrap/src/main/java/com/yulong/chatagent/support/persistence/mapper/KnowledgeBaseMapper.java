package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_base} table.
 */
@Mapper
public interface KnowledgeBaseMapper {
    int insert(KnowledgeBase knowledgeBase);

    List<KnowledgeBase> selectAll();

    List<KnowledgeBase> selectByIds(@Param("ids") List<String> ids);

    List<String> selectActiveIds(@Param("ids") List<String> ids);

    KnowledgeBase selectById(@Param("id") String id);

    int updateById(KnowledgeBase knowledgeBase);
}
