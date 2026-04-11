package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.KnowledgeBaseDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_base} table.
 */
@Mapper
public interface KnowledgeBaseMapper {
    int insert(KnowledgeBaseDTO knowledgeBase);

    List<KnowledgeBaseDTO> selectAll();

    List<KnowledgeBaseDTO> selectByIds(@Param("ids") List<String> ids);

    List<String> selectActiveIds(@Param("ids") List<String> ids);

    KnowledgeBaseDTO selectById(@Param("id") String id);

    int updateById(KnowledgeBaseDTO knowledgeBase);

    int deleteById(@Param("id") String id);
}
