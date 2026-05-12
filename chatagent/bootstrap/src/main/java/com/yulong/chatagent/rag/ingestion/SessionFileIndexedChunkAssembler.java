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
        if (StringUtils.hasText(title)) {
            return title;
        }

        String slideTitle = metadataString(metadata, "slideTitle");
        if (StringUtils.hasText(slideTitle)) {
            return slideTitle;
        }

        return null;
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

        String docType = metadataString(metadata, "docType");
        String locationLabel = resolveLocationLabel(metadata, docType);
        if (StringUtils.hasText(locationLabel)) {
            parts.add(locationLabel);
        }

        addIfPresent(parts, content);
        return String.join(System.lineSeparator() + System.lineSeparator(), parts);
    }

    private String resolveLocationLabel(Map<String, Object> metadata, String docType) {
        if ("powerpoint".equals(docType)) {
            return resolveSlideLabel(metadata);
        }
        if ("spreadsheet".equals(docType)) {
            return resolveSheetLabel(metadata);
        }
        return resolvePageLabel(metadata);
    }

    private String resolveSlideLabel(Map<String, Object> metadata) {
        String slideNumber = metadataString(metadata, "slideNumber");
        if (slideNumber == null) {
            String pageStart = metadataString(metadata, "pageStart");
            String pageEnd = metadataString(metadata, "pageEnd");
            if (StringUtils.hasText(pageStart) && StringUtils.hasText(pageEnd)) {
                return pageStart.equals(pageEnd) ? "Slide: " + pageStart : "Slides: " + pageStart + "-" + pageEnd;
            }
            return null;
        }
        String slideEnd = metadataString(metadata, "slideEnd");
        if (StringUtils.hasText(slideEnd) && !slideNumber.equals(slideEnd)) {
            return "Slides: " + slideNumber + "-" + slideEnd;
        }
        return "Slide: " + slideNumber;
    }

    private String resolveSheetLabel(Map<String, Object> metadata) {
        String sheetName = metadataString(metadata, "sheetName");
        String range = metadataString(metadata, "range");
        if (StringUtils.hasText(sheetName) && StringUtils.hasText(range)) {
            return "Sheet: " + sheetName + "  Range: " + range;
        }
        if (StringUtils.hasText(sheetName)) {
            return "Sheet: " + sheetName;
        }
        return null;
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
            log.warn("Failed to parse chunk metadata while assembling source-aware index document: error={}", e.getMessage());
            return Map.of();
        }
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
