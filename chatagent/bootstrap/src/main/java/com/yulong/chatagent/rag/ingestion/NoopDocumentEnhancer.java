package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;
import org.springframework.stereotype.Component;

/**
 * No-op {@link DocumentEnhancer} used when document enrichment is disabled; returns an empty
 * enhancement result so the ingestion pipeline always has a collaborator to call.
 */
@Component
public class NoopDocumentEnhancer implements DocumentEnhancer {

    @Override
    public DocumentEnhancementResult enhance(BaseIngestionContext context) {
        return DocumentEnhancementResult.empty();
    }
}
