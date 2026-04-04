package com.yulong.chatagent.rag.parser;

import java.util.Map;

/**
 * Runtime options for visual parsing.
 */
public record VdpOptions(
        boolean recognizeFormulas,
        String languageHint,
        Map<String, Object> extra
) {

    public VdpOptions {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
