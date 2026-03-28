package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusChunkDocument;
import org.springframework.stereotype.Component;

/**
 * Adapts source-aware index documents into the current session-scoped Milvus row schema.
 */
@Component
public class SessionScopedMilvusChunkMapper {

    public MilvusChunkDocument toMilvusDocument(IndexedChunkDocument document, float[] embedding) {
        if (document == null) {
            throw new IllegalArgumentException("Indexed chunk document is required");
        }
        if (document.sourceType() != RagSourceType.SESSION_FILE) {
            throw new IllegalArgumentException("Current Milvus collection only supports session-file chunk documents");
        }

        String retrievalText = document.resolvedRetrievalText();
        return new MilvusChunkDocument(
                document.chunkId(),
                document.scopeId(),
                document.sourceId(),
                document.resolvedChunkIndex(),
                document.resolvedDocumentName(),
                document.resolvedContent(),
                document.contextText(),
                retrievalText,
                retrievalText,
                document.enabled(),
                document.createdAtEpochMillis(),
                embedding
        );
    }
}
