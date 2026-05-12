package com.yulong.chatagent.rag.parser;

import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;

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
    @Deprecated(forRemoval = true)
    default ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        throw new UnsupportedOperationException("parse(byte[], String, Map) not implemented");
    }

    /**
     * Parses a stream-supplier source. The supplier must return a fresh readable stream each time.
     */
    ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options);

    /**
     * Lower values win when multiple parsers support the same detected file type.
     */
    default int getSelectionPriority() {
        ParserType parserType = ParserType.fromType(getParserType());
        if (parserType == null) {
            return 500;
        }
        return switch (parserType) {
            case MARKDOWN -> 10;
            case PDFBOX -> 20;
            case WORD -> 30;
            case POWERPOINT -> 40;
            case SPREADSHEET -> 50;
            case IMAGE -> 60;
            case TIKA -> 1000;
        };
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
    default boolean supports(DetectedFileType type) {
        return true;
    }
}
