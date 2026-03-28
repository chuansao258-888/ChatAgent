package com.yulong.chatagent.rag.service;

import com.yulong.chatagent.rag.model.CitationMetadata;

import java.util.List;

/**
 * Prompt text plus the aligned citation metadata rendered from retrieval hits.
 */
public record FormattedRetrievalPrompt(
        String promptText,
        List<CitationMetadata> citations
) {
}
