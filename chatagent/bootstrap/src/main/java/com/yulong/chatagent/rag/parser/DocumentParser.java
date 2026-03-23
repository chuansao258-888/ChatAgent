package com.yulong.chatagent.rag.parser;

import java.io.InputStream;
import java.util.Map;

/**
 * Strategy interface for parsing uploaded document bytes into normalized text.
 */
public interface DocumentParser {

    /**
     * Returns the logical parser type used by the selector.
     */
    String getParserType();

    /**
     * Parses raw document bytes.
     */
    default ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        throw new UnsupportedOperationException("parse(byte[], String, Map) not implemented");
    }

    /**
     * Extracts text from a stream-based source when a parser supports it.
     */
    default String extractText(InputStream stream, String fileName) {
        throw new UnsupportedOperationException("extractText(InputStream, String) not implemented");
    }

    /**
     * Returns whether this parser is a good fit for the given MIME type.
     */
    default boolean supports(String mimeType) {
        return true;
    }
}
