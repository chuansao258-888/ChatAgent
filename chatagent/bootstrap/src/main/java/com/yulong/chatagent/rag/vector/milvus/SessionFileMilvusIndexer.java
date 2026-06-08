package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.ingestion.SessionFileIndexedChunkAssembler;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts persisted session-file chunks into Milvus rows and upserts them when Milvus is enabled.
 */
@Service
@Slf4j
public class SessionFileMilvusIndexer {

    private final OllamaEmbeddingClient embeddingClient;
    private final ObjectProvider<MilvusIndexService> milvusIndexServiceProvider;
    private final SessionFileIndexedChunkAssembler indexedChunkAssembler;
    private final SessionScopedMilvusChunkMapper milvusChunkMapper;

    public SessionFileMilvusIndexer(OllamaEmbeddingClient embeddingClient,
                                    ObjectProvider<MilvusIndexService> milvusIndexServiceProvider,
                                    SessionFileIndexedChunkAssembler indexedChunkAssembler,
                                    SessionScopedMilvusChunkMapper milvusChunkMapper) {
        this.embeddingClient = embeddingClient;
        this.milvusIndexServiceProvider = milvusIndexServiceProvider;
        this.indexedChunkAssembler = indexedChunkAssembler;
        this.milvusChunkMapper = milvusChunkMapper;
    }

    /**
     * Embeds persisted chunks and mirrors them into the Milvus collection. The same retrieval text
     * is reused for dense embeddings and BM25 sparse indexing to keep both retrieval paths aligned.
     */
    public void upsert(String sessionId, ChatSessionFileDTO sessionFile, List<FileChunkDTO> chunks) {
        long startTime = System.nanoTime();
        MilvusIndexService milvusIndexService = milvusIndexServiceProvider.getIfAvailable();
        if (milvusIndexService == null || chunks == null || chunks.isEmpty()) {
            log.info("Milvus indexing skipped: sessionId={}, sessionFileId={}, chunkCount={}, reason={}",
                    sessionId,
                    sessionFile == null ? null : sessionFile.getId(),
                    chunks == null ? 0 : chunks.size(),
                    milvusIndexService == null ? "milvus-disabled" : "no-chunks");
            return;
        }

        log.info("Milvus indexing started: sessionId={}, sessionFileId={}, chunkCount={}, filename={}",
                sessionId,
                sessionFile.getId(),
                chunks.size(),
                sessionFile.getOriginalFilename());
        List<IndexedChunkDocument> indexedChunks = indexedChunkAssembler.assemble(sessionId, sessionFile, chunks);
        List<MilvusChunkDocument> documents = new ArrayList<>(indexedChunks.size());
        embedChunksInBatches(indexedChunks, documents);

        milvusIndexService.upsertChunks(documents);
        log.info("Milvus indexing finished: sessionId={}, sessionFileId={}, chunkCount={}, durationMs={}",
                sessionId,
                sessionFile.getId(),
                documents.size(),
                (System.nanoTime() - startTime) / 1_000_000);
    }

    public void deleteBySessionFileId(String sessionFileId) {
        MilvusIndexService milvusIndexService = milvusIndexServiceProvider.getIfAvailable();
        if (milvusIndexService != null) {
            milvusIndexService.deleteBySessionFileId(sessionFileId);
        }
    }

    public void deleteBySessionId(String sessionId) {
        MilvusIndexService milvusIndexService = milvusIndexServiceProvider.getIfAvailable();
        if (milvusIndexService != null) {
            milvusIndexService.deleteBySessionId(sessionId);
        }
    }

    // ── Batch embedding ────────────────────────────────────────────────

    private static final int BATCH_SIZE = 32;

    private void embedChunksInBatches(List<IndexedChunkDocument> indexedChunks,
                                      List<MilvusChunkDocument> documents) {
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
