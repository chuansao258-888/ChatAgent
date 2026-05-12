package com.yulong.chatagent.rag.parser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // getFullText 是旧测试/诊断便捷入口，生产摄取主链消费结构化 segments。
    // 测试侧现在直接按 segments 拼接，避免模型对象暴露非主链 API。
    // public String getFullText() {
    //     if (segments == null || segments.isEmpty()) {
    //         return "";
    //     }
    //     return segments.stream()
    //             .map(ParseSegment::text)
    //             .filter(StringUtils::hasText)
    //             .collect(Collectors.joining("\n\n"));
    // }

    public int totalChars() {
        if (segments != null && !segments.isEmpty()) {
            return segments.stream().mapToInt(ParseSegment::charCount).sum();
        }
        return 0;
    }

    // 旧便捷判断已停用：当前摄取流程直接使用 segments/totalChars，
    // 没有独立依赖“是否多段”的分支。
    // public boolean isMultiSegment() {
    //     return segments != null && segments.size() > 1;
    // }
}
