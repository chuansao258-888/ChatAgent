package com.yulong.chatagent.rag.parser;

/**
 * Identifies which ingestion pipeline is requesting parser/file-type resolution.
 */
public enum PipelineSource {
    SESSION,
    KNOWLEDGE
}
