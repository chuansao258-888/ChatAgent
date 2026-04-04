package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default chunk enricher used when contextual enrichment is disabled.
 */
@Component
public class NoopChunkEnricher implements ChunkEnricher {

    @Override
    public List<KnowledgeChunkDraft> enrich(BaseIngestionContext context, List<KnowledgeChunkDraft> drafts) {
        return drafts;
    }
}
