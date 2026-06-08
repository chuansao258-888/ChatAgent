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
        embedChunksInBatches(indexedChunks, documents);

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

    // ── Batch embedding ────────────────────────────────────────────────

    private static final int BATCH_SIZE = 32;

    private void embedChunksInBatches(List<IndexedChunkDocument> indexedChunks,
                                      List<KnowledgeBaseMilvusChunkDocument> documents) {
        for (int batchStart = 0; batchStart < indexedChunks.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, indexedChunks.size());
            List<String> texts = new ArrayList<>(batchEnd - batchStart);
            for (int i = batchStart; i < batchEnd; i++) {
                texts.add(indexedChunks.get(i).resolvedRetrievalText());
            }
            float[][] embeddings = embeddingClient.embedBatch(texts);
            for (int i = batchStart; i < batchEnd; i++) {
                int batchIdx = i - batchStart;
                documents.add(milvusChunkMapper.toMilvusDocument(
                        indexedChunks.get(i), embeddings[batchIdx]));
            }
        }
    }
}
