package com.yulong.chatagent.rag.parser;

import java.util.Map;

/**
 * Normalized parser output containing extracted text plus optional metadata.
 */
public record ParseResult(String text, Map<String, Object> metadata) {

    /**
     * Creates a text-only parse result.
     */
    public static ParseResult ofText(String text) {
        return new ParseResult(text, Map.of());
    }

    /**
     * Creates a parse result with text and metadata.
     */
    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text, metadata != null ? metadata : Map.of());
    }
}
