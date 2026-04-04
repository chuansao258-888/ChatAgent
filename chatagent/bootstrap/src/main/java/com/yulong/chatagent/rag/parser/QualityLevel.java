package com.yulong.chatagent.rag.parser;

/**
 * Coarse parser quality assessment used by ingestion guard logic.
 */
public enum QualityLevel {
    HIGH,
    MEDIUM,
    LOW,
    REJECTED
}
