package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.KnowledgeDocumentEnhancement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_document_enhancement} table.
 */
@Mapper
public interface KnowledgeDocumentEnhancementMapper {

    KnowledgeDocumentEnhancement selectByKnowledgeDocumentId(@Param("knowledgeDocumentId") String knowledgeDocumentId);

    List<KnowledgeDocumentEnhancement> selectByKnowledgeDocumentIds(@Param("knowledgeDocumentIds") List<String> knowledgeDocumentIds);

    int upsert(KnowledgeDocumentEnhancement enhancement);

    int deleteByKnowledgeDocumentId(@Param("knowledgeDocumentId") String knowledgeDocumentId);
}
