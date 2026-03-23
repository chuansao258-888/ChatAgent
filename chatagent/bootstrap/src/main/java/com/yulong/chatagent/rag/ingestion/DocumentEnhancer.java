package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.FileIngestionContext;

/**
 * Optional document-level text post-processor executed between parsing and chunking.
 */
public interface DocumentEnhancer {

    /**
     * Returns the text that should be used as the chunking source.
     */
    String enhance(FileIngestionContext context, String rawText);
}
