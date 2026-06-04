package com.yulong.chatagent.memory.application;

import java.util.List;

/**
 * Result of an L3 memory extraction call.
 *
 * @param memories extracted memory candidates (may be empty, never null)
 * @param success  whether the LLM call succeeded and produced parseable output
 */
public record ExtractionResult(
        List<ExtractedMemory> memories,
        boolean success
) {
    public ExtractionResult {
        if (memories == null) {
            memories = List.of();
        }
    }

    public static ExtractionResult success(List<ExtractedMemory> memories) {
        return new ExtractionResult(memories, true);
    }

    public static ExtractionResult failure() {
        return new ExtractionResult(List.of(), false);
    }
}
