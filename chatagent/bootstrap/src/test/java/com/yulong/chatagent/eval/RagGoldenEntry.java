package com.yulong.chatagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Golden dataset entry for RAG retrieval quality evaluation.
 * Maps to eval/golden/rag-golden.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagGoldenEntry(
        String id,
        String category,              // factual | multi-hop | comparison | temporal
        String domain,                // hr | finance | it | admin
        String query,
        List<String> expectedDocumentIds,
        List<String> expectedAnswerFragments,
        Map<String, Integer> relevanceGrades  // documentId -> {3=highly relevant, 2=somewhat, 1=marginally, 0=irrelevant}
) {}
