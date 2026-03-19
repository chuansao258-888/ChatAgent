package com.yulong.chatagent.support.persistence.adapter.rag;

import com.yulong.chatagent.rag.repository.KnowledgeChunkSearchRepository;
import com.yulong.chatagent.support.persistence.entity.ChunkBgeM3;
import com.yulong.chatagent.support.persistence.mapper.ChunkBgeM3Mapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis-backed implementation of chunk similarity search.
 */
@Repository
public class MyBatisKnowledgeChunkSearchRepository implements KnowledgeChunkSearchRepository {

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    public MyBatisKnowledgeChunkSearchRepository(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Override
    public List<String> similaritySearch(String kbId, String vectorLiteral, int limit) {
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, vectorLiteral, limit);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }
}
