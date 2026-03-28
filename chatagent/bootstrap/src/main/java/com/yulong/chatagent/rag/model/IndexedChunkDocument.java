package com.yulong.chatagent.rag.model;

/**
 * Source-aware chunk document prepared for indexing before it is adapted to a concrete vector
 * store schema.
 */
public record IndexedChunkDocument(
        String chunkId,
        RagSourceType sourceType,
        String scopeId,
        String sourceId,
        String documentId,
        String documentName,
        Integer chunkIndex,
        String sectionPath,
        String content,
        String contextText,
        String retrievalText,
        boolean enabled,
        long createdAtEpochMillis
) {

    public int resolvedChunkIndex() {
        return chunkIndex == null ? 0 : chunkIndex;
    }

    public String resolvedContent() {
        return content == null ? "" : content;
    }

    public String resolvedRetrievalText() {
        if (retrievalText != null && !retrievalText.isBlank()) {
            return retrievalText;
        }
        return resolvedContent();
    }

    public String resolvedDocumentName() {
        if (documentName != null && !documentName.isBlank()) {
            return documentName;
        }
        if (documentId != null && !documentId.isBlank()) {
            return documentId;
        }
        return sourceId;
    }
}
