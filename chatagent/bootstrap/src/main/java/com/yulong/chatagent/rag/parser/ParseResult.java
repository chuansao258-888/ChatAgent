package com.yulong.chatagent.rag.parser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Normalized parser output for the segment-native parser pipeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    @Builder.Default
    private List<ParseSegment> segments = List.of();

    private String parserType;
    private String extractionMode;
    private QualityLevel qualityLevel;

    @Builder.Default
    private Map<String, Object> diagnostics = new HashMap<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public static ParseResult ofText(String text) {
        String normalized = text == null ? "" : text;
        return ParseResult.builder()
                .segments(List.of(new ParseSegment(normalized, 0, SegmentType.FULL, Map.of())))
                .build();
    }

    public static ParseResult of(String text, Map<String, Object> metadata) {
        ParseResult result = ofText(text);
        result.setMetadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        return result;
    }

    public String getFullText() {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        return segments.stream()
                .map(ParseSegment::text)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n"));
    }

    public int totalChars() {
        if (segments != null && !segments.isEmpty()) {
            return segments.stream().mapToInt(ParseSegment::charCount).sum();
        }
        return 0;
    }

    public boolean isMultiSegment() {
        return segments != null && segments.size() > 1;
    }
}
