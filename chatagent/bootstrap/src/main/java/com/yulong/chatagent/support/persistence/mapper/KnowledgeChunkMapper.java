package com.yulong.chatagent.support.persistence.mapper;

import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper for the {@code knowledge_chunk} table.
 */
@Mapper
public interface KnowledgeChunkMapper {
    int insert(KnowledgeChunkDTO knowledgeChunk);

    List<KnowledgeChunkDTO> selectByKnowledgeDocumentId(@Param("knowledgeDocumentId") String knowledgeDocumentId);

    int deleteByKnowledgeDocumentId(@Param("knowledgeDocumentId") String knowledgeDocumentId);
}
