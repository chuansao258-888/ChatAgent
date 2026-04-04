package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParseResultTest {

    @Test
    void shouldDeriveFullTextFromSegments() {
        ParseResult result = ParseResult.builder()
                .segments(List.of(
                        new ParseSegment("first block", 0, SegmentType.FULL, Map.of()),
                        new ParseSegment("second block", 1, SegmentType.FULL, Map.of())
                ))
                .build();

        assertThat(result.getFullText()).isEqualTo("first block\n\nsecond block");
        assertThat(result.totalChars()).isEqualTo("first block".length() + "second block".length());
    }
}
