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
    MARKDOWN("Markdown");

    /**
     * Stable parser type name used by the selector.
     */
    private final String type;
}
