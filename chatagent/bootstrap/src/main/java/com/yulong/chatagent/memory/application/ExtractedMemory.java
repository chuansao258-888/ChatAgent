package com.yulong.chatagent.memory.application;

import java.util.List;

/**
 * A single memory candidate extracted by the L3 extractor from conversation turns.
 *
 * @param type    one of {@code "preference"} or {@code "fact"}
 * @param content the atomic memory statement
 * @param tags    optional categorization tags (may be empty, never null)
 */
public record ExtractedMemory(
        String type,
        String content,
        List<String> tags
) {
    public ExtractedMemory {
        if (tags == null) {
            tags = List.of();
        }
    }
}
