package com.yulong.chatagent.rag.parser;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Supported parser strategy identifiers.
 */
@Getter
@RequiredArgsConstructor
public enum ParserType {

    /**
     * Generic parser backed by Apache Tika.
     */
    TIKA("Tika"),

    /**
     * Specialized parser for markdown sources.
     */
    MARKDOWN("Markdown"),

    /**
     * Specialized parser for standalone image sources.
     */
    IMAGE("Image"),

    /**
     * Specialized parser for PDF sources.
     */
    PDFBOX("PDFBox");

    /**
     * Stable parser type name used by the selector.
     */
    private final String type;

    public static ParserType fromType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        for (ParserType parserType : values()) {
            if (parserType.type.equalsIgnoreCase(type.trim())) {
                return parserType;
            }
        }
        return null;
    }
}
