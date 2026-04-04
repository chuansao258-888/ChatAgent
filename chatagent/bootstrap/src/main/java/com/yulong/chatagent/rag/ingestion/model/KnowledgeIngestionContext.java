package com.yulong.chatagent.rag.ingestion.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/**
 * Future segment-native context for knowledge-base ingestion.
 */
@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class KnowledgeIngestionContext extends BaseIngestionContext {

    private String documentId;
    private String knowledgeBaseId;
    private List<String> keywords;
    private List<String> questions;
    private Map<String, Object> enhancerMetadata;
    private String enhancerCacheKey;
}
