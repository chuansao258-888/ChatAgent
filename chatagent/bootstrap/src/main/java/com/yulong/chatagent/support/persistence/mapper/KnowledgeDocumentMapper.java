package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_document} table.
 */
@Mapper
public interface KnowledgeDocumentMapper {
    int insert(KnowledgeDocument knowledgeDocument);

    List<KnowledgeDocument> selectByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    KnowledgeDocument selectById(@Param("id") String id);

    int updateById(KnowledgeDocument knowledgeDocument);

    int deleteById(@Param("id") String id);
}
