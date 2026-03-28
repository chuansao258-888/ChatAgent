package com.yulong.chatagent.rag.vector.milvus;

import com.yulong.chatagent.rag.model.IndexedChunkDocument;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.vector.milvus.model.KnowledgeBaseMilvusChunkDocument;
import org.springframework.stereotype.Component;

/**
 * Adapts source-aware knowledge-base chunk documents into the knowledge-base Milvus row schema.
 */
@Component
public class KnowledgeBaseMilvusChunkMapper {

    public KnowledgeBaseMilvusChunkDocument toMilvusDocument(IndexedChunkDocument document, float[] embedding) {
        if (document == null) {
            throw new IllegalArgumentException("Indexed chunk document is required");
        }
        if (document.sourceType() != RagSourceType.KNOWLEDGE_BASE) {
            throw new IllegalArgumentException("Knowledge-base collection only supports knowledge-base chunk documents");
        }

        String retrievalText = document.resolvedRetrievalText();
        return new KnowledgeBaseMilvusChunkDocument(
                document.chunkId(),
                document.sourceId(),
                document.documentId(),
                document.resolvedChunkIndex(),
                document.resolvedDocumentName(),
                document.sectionPath(),
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
