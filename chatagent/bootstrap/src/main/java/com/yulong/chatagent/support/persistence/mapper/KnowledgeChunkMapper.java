package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.persistence.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_chunk} table.
 */
@Mapper
public interface KnowledgeChunkMapper {
    int insert(KnowledgeChunk knowledgeChunk);

    List<KnowledgeChunk> selectByKnowledgeDocumentId(@Param("knowledgeDocumentId") String knowledgeDocumentId);

    int deleteByKnowledgeDocumentId(@Param("knowledgeDocumentId") String knowledgeDocumentId);
}
