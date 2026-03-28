package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds source-aware index documents from persisted session-file chunks so future knowledge-base
 * indexers can reuse the same contract before adapting to a specific vector-store schema.
 */
@Component
@Slf4j
public class SessionFileIndexedChunkAssembler {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public SessionFileIndexedChunkAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<IndexedChunkDocument> assemble(String sessionId, ChatSessionFileDTO sessionFile, List<FileChunkDTO> chunks) {
        if (!StringUtils.hasText(sessionId) || sessionFile == null || chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<IndexedChunkDocument> documents = new ArrayList<>(chunks.size());
        for (FileChunkDTO chunk : chunks) {
            documents.add(toIndexedChunkDocument(sessionId, sessionFile, chunk));
        }
        return documents;
    }

    private IndexedChunkDocument toIndexedChunkDocument(String sessionId, ChatSessionFileDTO sessionFile, FileChunkDTO chunk) {
        Map<String, Object> metadata = parseMetadata(chunk == null ? null : chunk.getMetadata());
        String content = chunk == null || chunk.getContent() == null ? "" : chunk.getContent();
        return new IndexedChunkDocument(
                chunk == null ? null : chunk.getId(),
                RagSourceType.SESSION_FILE,
                sessionId,
                sessionFile.getId(),
                sessionFile.getId(),
                resolveDocumentName(sessionFile),
                chunk == null ? null : chunk.getChunkIndex(),
                resolveSectionPath(metadata),
                content,
                metadataString(metadata, "contextText"),
                resolveRetrievalText(content, metadata),
                chunk == null || chunk.getEnabled() == null || chunk.getEnabled(),
                chunk == null || chunk.getCreatedAt() == null
                        ? System.currentTimeMillis()
                        : chunk.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
        );
    }

    private String resolveDocumentName(ChatSessionFileDTO sessionFile) {
        if (sessionFile == null) {
            return null;
        }
        if (StringUtils.hasText(sessionFile.getOriginalFilename())) {
            return sessionFile.getOriginalFilename();
        }
        if (StringUtils.hasText(sessionFile.getFilename())) {
            return sessionFile.getFilename();
        }
        return sessionFile.getId();
    }

    private String resolveSectionPath(Map<String, Object> metadata) {
        String sectionPath = metadataString(metadata, "sectionPath");
        if (StringUtils.hasText(sectionPath)) {
            return sectionPath;
        }

        String headingPath = metadataString(metadata, "headingPath");
        if (StringUtils.hasText(headingPath)) {
            return headingPath;
        }

        String title = metadataString(metadata, "title");
        return StringUtils.hasText(title) ? title : null;
    }

    private String resolveRetrievalText(String content, Map<String, Object> metadata) {
        String retrievalText = metadataString(metadata, "retrievalText");
        if (StringUtils.hasText(retrievalText)) {
            return retrievalText;
        }
        return content;
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse chunk metadata while assembling source-aware index document: error={}", e.getMessage());
            return Map.of();
        }
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
