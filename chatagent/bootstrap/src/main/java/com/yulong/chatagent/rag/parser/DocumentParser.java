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
    default ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        throw new UnsupportedOperationException("parse(byte[], String, Map) not implemented");
    }

    /**
     * Parses a stream-supplier source. The supplier must return a fresh readable stream each time.
     */
    default ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                throw new IllegalArgumentException("streamSupplier returned null");
            }
            // TODO Phase 5b: remove byte[] bridge once every parser implements stream-native parsing.
            return parse(stream.readAllBytes(), mimeType, options);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse stream-based document source", e);
        }
    }

    /**
     * Lower values win when multiple parsers support the same detected file type.
     */
    default int getSelectionPriority() {
        return switch (getParserType()) {
            case "Markdown" -> 10;
            case "PDFBox" -> 20;
            case "Image" -> 30;
            case "Tika" -> 1000;
            default -> 500;
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
