package com.yulong.chatagent.rag.parser;

import java.util.Map;

/**
 * Normalized result for one visually parsed page or standalone image.
 */
public record VdpPageResult(
        int pageIndex,
        String markdown,
        VdpPageStatus status,
        Map<String, Object> metadata
) {

    public VdpPageResult {
        markdown = markdown == null ? "" : markdown;
        status = status == null ? VdpPageStatus.FAILED : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
