package com.yulong.chatagent.rag.vector.milvus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts persisted session-file chunks into Milvus rows and upserts them when Milvus is enabled.
 */
@Service
@Slf4j
public class SessionFileMilvusIndexer {

    private final OllamaEmbeddingClient embeddingClient;
    private final ObjectProvider<MilvusIndexService> milvusIndexServiceProvider;
    private final ObjectMapper objectMapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public SessionFileMilvusIndexer(OllamaEmbeddingClient embeddingClient,
                                    ObjectProvider<MilvusIndexService> milvusIndexServiceProvider,
                                    ObjectMapper objectMapper) {
        this.embeddingClient = embeddingClient;
        this.milvusIndexServiceProvider = milvusIndexServiceProvider;
        this.objectMapper = objectMapper;
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
        List<MilvusChunkDocument> documents = new ArrayList<>(chunks.size());
        for (FileChunkDTO chunk : chunks) {
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            Map<String, Object> metadata = parseMetadata(chunk.getMetadata());
            String contextText = metadataString(metadata, "contextText");
            String retrievalText = metadataString(metadata, "retrievalText");
            if (!StringUtils.hasText(retrievalText)) {
                // Legacy chunks may only store the raw content. In that case the chunk content is
                // still a valid retrieval source for both dense and sparse search.
                retrievalText = content;
            }
            documents.add(new MilvusChunkDocument(
                    chunk.getId(),
                    sessionId,
                    sessionFile.getId(),
                    chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex(),
                    sessionFile.getOriginalFilename(),
                    content,
                    contextText,
                    retrievalText,
                    retrievalText,
                    chunk.getEnabled() == null || chunk.getEnabled(),
                    chunk.getCreatedAt() == null ? System.currentTimeMillis() : chunk.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    embeddingClient.embed(retrievalText)
            ));
        }

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

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse chunk metadata before Milvus indexing: error={}", e.getMessage());
            return Map.of();
        }
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
