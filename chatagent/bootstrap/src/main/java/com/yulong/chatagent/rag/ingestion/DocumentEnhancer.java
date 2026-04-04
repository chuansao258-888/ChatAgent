package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.BaseIngestionContext;

/**
 * Optional document-level text post-processor executed between parsing and chunking.
 */
public interface DocumentEnhancer {

    /**
     * Returns transient enhancement output that should be unpacked into the ingestion context
     * before chunking.
     */
    DocumentEnhancementResult enhance(BaseIngestionContext context);
}
