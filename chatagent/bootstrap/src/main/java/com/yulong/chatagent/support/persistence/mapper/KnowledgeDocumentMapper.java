package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_document} table.
 */
@Mapper
public interface KnowledgeDocumentMapper {
    int insert(KnowledgeDocumentDTO knowledgeDocument);

    List<KnowledgeDocumentDTO> selectByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);

    KnowledgeDocumentDTO selectById(@Param("id") String id);

    int updateById(KnowledgeDocumentDTO knowledgeDocument);

    int deleteById(@Param("id") String id);
}
