package com.yulong.chatagent.rag.parser;

import java.util.Map;

/**
 * Atomic parser output unit that keeps minimal structure and positional metadata.
 */
public record ParseSegment(
        String text,
        int index,
        SegmentType type,
        Map<String, Object> metadata
) {

    public ParseSegment {
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        type = type == null ? SegmentType.FULL : type;
    }

    public int charCount() {
        return text.length();
    }
}
