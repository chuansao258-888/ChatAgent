package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;

import java.util.List;

/**
 * Optional chunk-level enrichment hook executed after chunking and before persistence/indexing.
 */
public interface ChunkEnricher {

    /**
     * Returns the chunk drafts to persist. Implementations may rewrite metadata, embedding text,
     * or retrieval-specific context while keeping the original chunk order.
     */
    List<KnowledgeChunkDraft> enrich(BaseIngestionContext context, List<KnowledgeChunkDraft> drafts);
}
