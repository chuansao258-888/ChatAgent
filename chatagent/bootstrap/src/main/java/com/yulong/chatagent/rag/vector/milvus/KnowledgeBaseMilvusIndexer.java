package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.ingestion.KnowledgeBaseIndexedChunkAssembler;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts persisted knowledge-base chunks into Milvus rows and upserts them when Milvus is enabled.
 */
@Service
@Slf4j
public class KnowledgeBaseMilvusIndexer {

    private final OllamaEmbeddingClient embeddingClient;
    private final ObjectProvider<KnowledgeBaseMilvusIndexService> knowledgeBaseMilvusIndexServiceProvider;
    private final KnowledgeBaseIndexedChunkAssembler indexedChunkAssembler;
    private final KnowledgeBaseMilvusChunkMapper milvusChunkMapper;

    public KnowledgeBaseMilvusIndexer(OllamaEmbeddingClient embeddingClient,
                                      ObjectProvider<KnowledgeBaseMilvusIndexService> knowledgeBaseMilvusIndexServiceProvider,
                                      KnowledgeBaseIndexedChunkAssembler indexedChunkAssembler,
                                      KnowledgeBaseMilvusChunkMapper milvusChunkMapper) {
        this.embeddingClient = embeddingClient;
        this.knowledgeBaseMilvusIndexServiceProvider = knowledgeBaseMilvusIndexServiceProvider;
        this.indexedChunkAssembler = indexedChunkAssembler;
        this.milvusChunkMapper = milvusChunkMapper;
    }

    public void upsert(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument, List<KnowledgeChunkDTO> chunks) {
        long startTime = System.nanoTime();
        KnowledgeBaseMilvusIndexService indexService = knowledgeBaseMilvusIndexServiceProvider.getIfAvailable();
        if (indexService == null || chunks == null || chunks.isEmpty()) {
            log.info("Knowledge-base Milvus indexing skipped: knowledgeBaseId={}, documentId={}, chunkCount={}, reason={}",
                    knowledgeBaseId,
                    knowledgeDocument == null ? null : knowledgeDocument.getId(),
                    chunks == null ? 0 : chunks.size(),
                    indexService == null ? "milvus-disabled" : "no-chunks");
            return;
        }

        log.info("Knowledge-base Milvus indexing started: knowledgeBaseId={}, documentId={}, chunkCount={}",
                knowledgeBaseId,
                knowledgeDocument == null ? null : knowledgeDocument.getId(),
                chunks.size());
        List<IndexedChunkDocument> indexedChunks = indexedChunkAssembler.assemble(knowledgeBaseId, knowledgeDocument, chunks);
        List<KnowledgeBaseMilvusChunkDocument> documents = new ArrayList<>(indexedChunks.size());
        for (IndexedChunkDocument indexedChunk : indexedChunks) {
            documents.add(milvusChunkMapper.toMilvusDocument(
                    indexedChunk,
                    embeddingClient.embed(indexedChunk.resolvedRetrievalText())
            ));
        }

        indexService.upsertChunks(documents);
        log.info("Knowledge-base Milvus indexing finished: knowledgeBaseId={}, documentId={}, chunkCount={}, durationMs={}",
                knowledgeBaseId,
                knowledgeDocument == null ? null : knowledgeDocument.getId(),
                documents.size(),
                (System.nanoTime() - startTime) / 1_000_000);
    }

    public void deleteByKnowledgeDocumentId(String knowledgeDocumentId) {
        KnowledgeBaseMilvusIndexService indexService = knowledgeBaseMilvusIndexServiceProvider.getIfAvailable();
        if (indexService == null) {
            log.info("Knowledge-base Milvus delete skipped: knowledgeDocumentId={}, reason=milvus-disabled",
                    knowledgeDocumentId);
            return;
        }
        log.info("Knowledge-base Milvus delete started: knowledgeDocumentId={}", knowledgeDocumentId);
        indexService.deleteByKnowledgeDocumentId(knowledgeDocumentId);
        log.info("Knowledge-base Milvus delete completed: knowledgeDocumentId={}", knowledgeDocumentId);
    }

    public void deleteByKnowledgeBaseId(String knowledgeBaseId) {
        KnowledgeBaseMilvusIndexService indexService = knowledgeBaseMilvusIndexServiceProvider.getIfAvailable();
        if (indexService == null) {
            log.info("Knowledge-base Milvus delete skipped: knowledgeBaseId={}, reason=milvus-disabled",
                    knowledgeBaseId);
            return;
        }
        log.info("Knowledge-base Milvus delete started: knowledgeBaseId={}", knowledgeBaseId);
        indexService.deleteByKnowledgeBaseId(knowledgeBaseId);
        log.info("Knowledge-base Milvus delete completed: knowledgeBaseId={}", knowledgeBaseId);
    }
}
