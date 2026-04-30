package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds source-aware index documents from persisted knowledge-base chunks.
 */
@Component
@Slf4j
public class KnowledgeBaseIndexedChunkAssembler {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public KnowledgeBaseIndexedChunkAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<IndexedChunkDocument> assemble(String knowledgeBaseId,
                                               KnowledgeDocumentDTO knowledgeDocument,
                                               List<KnowledgeChunkDTO> chunks) {
        if (!StringUtils.hasText(knowledgeBaseId) || knowledgeDocument == null || chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<IndexedChunkDocument> documents = new ArrayList<>(chunks.size());
        for (KnowledgeChunkDTO chunk : chunks) {
            documents.add(toIndexedChunkDocument(knowledgeBaseId, knowledgeDocument, chunk));
        }
        return documents;
    }

    private IndexedChunkDocument toIndexedChunkDocument(String knowledgeBaseId,
                                                        KnowledgeDocumentDTO knowledgeDocument,
                                                        KnowledgeChunkDTO chunk) {
        Map<String, Object> metadata = parseMetadata(chunk == null ? null : chunk.getMetadata());
        String content = chunk == null || chunk.getContent() == null ? "" : chunk.getContent();
        return new IndexedChunkDocument(
                chunk == null ? null : chunk.getId(),
                RagSourceType.KNOWLEDGE_BASE,
                knowledgeBaseId,
                knowledgeBaseId,
                knowledgeDocument.getId(),
                resolveDocumentName(knowledgeDocument),
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

    private String resolveDocumentName(KnowledgeDocumentDTO knowledgeDocument) {
        if (knowledgeDocument == null) {
            return null;
        }
        if (StringUtils.hasText(knowledgeDocument.getOriginalFilename())) {
            return knowledgeDocument.getOriginalFilename();
        }
        if (StringUtils.hasText(knowledgeDocument.getFilename())) {
            return knowledgeDocument.getFilename();
        }
        return knowledgeDocument.getId();
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

        List<String> parts = new ArrayList<>();
        addIfPresent(parts, metadataString(metadata, "contextText"));
        String sectionPath = resolveSectionPath(metadata);
        if (StringUtils.hasText(sectionPath)) {
            parts.add("Section: " + sectionPath);
        }
        String pageLabel = resolvePageLabel(metadata);
        if (StringUtils.hasText(pageLabel)) {
            parts.add(pageLabel);
        }
        addIfPresent(parts, content);
        return String.join(System.lineSeparator() + System.lineSeparator(), parts);
    }

    private String resolvePageLabel(Map<String, Object> metadata) {
        String pageStart = metadataString(metadata, "pageStart");
        String pageEnd = metadataString(metadata, "pageEnd");
        if (StringUtils.hasText(pageStart) && StringUtils.hasText(pageEnd)) {
            return pageStart.equals(pageEnd) ? "Page: " + pageStart : "Pages: " + pageStart + "-" + pageEnd;
        }
        return null;
    }

    private void addIfPresent(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse knowledge chunk metadata while assembling index document: error={}", e.getMessage());
            return Map.of();
        }
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
