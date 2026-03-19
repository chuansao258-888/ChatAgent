package com.yulong.chatagent.support.persistence.adapter.rag;

import com.yulong.chatagent.rag.model.KnowledgeChunk;
import com.yulong.chatagent.rag.repository.DocumentChunkRepository;
import com.yulong.chatagent.support.persistence.entity.ChunkBgeM3;
import com.yulong.chatagent.support.persistence.mapper.ChunkBgeM3Mapper;
import org.springframework.stereotype.Repository;

/**
 * MyBatis-backed implementation of chunk persistence.
 */
@Repository
public class MyBatisDocumentChunkRepository implements DocumentChunkRepository {

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    public MyBatisDocumentChunkRepository(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Override
    public void save(KnowledgeChunk chunk) {
        chunkBgeM3Mapper.insert(ChunkBgeM3.builder()
                .kbId(chunk.kbId())
                .docId(chunk.documentId())
                .content(chunk.content())
                .metadata(chunk.metadata())
                .embedding(chunk.embedding())
                .createdAt(chunk.createdAt())
                .updatedAt(chunk.updatedAt())
                .build());
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        chunkBgeM3Mapper.deleteByDocId(documentId);
    }
}
