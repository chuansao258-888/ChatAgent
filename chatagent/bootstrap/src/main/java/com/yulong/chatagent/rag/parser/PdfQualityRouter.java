package com.yulong.chatagent.rag.parser;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether each PDF page should follow the native text path or the
 * visual dispatch (VDP) track based on text density, structure, and alignment.
 */
class PdfQualityRouter {

    private static final Pattern ALIGNED_WHITESPACE_PATTERN = Pattern.compile(" {3,}");
    private static final Pattern SENTENCE_PUNCTUATION_PATTERN = Pattern.compile("[。！？；;.!?]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");

    private final int charDensityThreshold;
    private final int shortTextFastTrackThreshold;
    private final int whitespaceAlignedLineThreshold;

    PdfQualityRouter(int charDensityThreshold, int shortTextFastTrackThreshold, int whitespaceAlignedLineThreshold) {
        this.charDensityThreshold = Math.max(1, charDensityThreshold);
        this.shortTextFastTrackThreshold = Math.max(1, shortTextFastTrackThreshold);
        this.whitespaceAlignedLineThreshold = Math.max(1, whitespaceAlignedLineThreshold);
    }

    PageRoutingDecision decideRoute(String nativeText) {
        String trimmed = nativeText == null ? "" : nativeText.trim();
        String normalized = normalizeInlineWhitespace(trimmed);
        int alignedWhitespaceLines = countAlignedWhitespaceLines(trimmed);
        if (!StringUtils.hasText(trimmed)) {
            return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "EMPTY_NATIVE_TEXT", alignedWhitespaceLines);
        }
        if (alignedWhitespaceLines >= whitespaceAlignedLineThreshold) {
            return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "ALIGNED_WHITESPACE", alignedWhitespaceLines);
        }
        if (trimmed.length() <= shortTextFastTrackThreshold) {
            if (looksLikeStructuredShortText(normalized)) {
                return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "SHORT_STRUCTURED_TEXT", alignedWhitespaceLines);
            }
            return new PageRoutingDecision(PageRoute.FAST_TRACK, "SHORT_TEXT_FAST_TRACK", alignedWhitespaceLines);
        }
        if (trimmed.length() < charDensityThreshold && !looksLikeNarrativeSnippet(normalized)) {
            return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "LOW_CHAR_DENSITY", alignedWhitespaceLines);
        }
        if (trimmed.length() < charDensityThreshold) {
            return new PageRoutingDecision(PageRoute.FAST_TRACK, "SHORT_NARRATIVE_FAST_TRACK", alignedWhitespaceLines);
        }
        return new PageRoutingDecision(PageRoute.FAST_TRACK, "NATIVE_TEXT", alignedWhitespaceLines);
    }

    List<String> summarizeVisualTrackPages(List<PageRoutingDecision> routingDecisions) {
        if (routingDecisions == null || routingDecisions.isEmpty()) {
            return List.of();
        }
        List<String> visualTrackPages = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < routingDecisions.size(); pageIndex++) {
            PageRoutingDecision decision = routingDecisions.get(pageIndex);
            if (decision == null || !decision.isVisualTrack()) {
                continue;
            }
            visualTrackPages.add((pageIndex + 1) + ":" + decision.reason());
        }
        return visualTrackPages;
    }

    private String normalizeInlineWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ");
    }

    private boolean looksLikeNarrativeSnippet(String normalizedText) {
        if (!StringUtils.hasText(normalizedText)) {
            return false;
        }
        return SENTENCE_PUNCTUATION_PATTERN.matcher(normalizedText).find();
    }

    private boolean looksLikeStructuredShortText(String normalizedText) {
        if (!StringUtils.hasText(normalizedText)) {
            return false;
        }
        if (normalizedText.contains("|") || normalizedText.contains("\t")) {
            return true;
        }
        if (!DIGIT_PATTERN.matcher(normalizedText).find()) {
            return false;
        }
        String[] tokens = normalizedText.split(" ");
        if (tokens.length < 3) {
            return false;
        }
        int compactTokenCount = 0;
        for (String token : tokens) {
            if (token.length() <= 4) {
                compactTokenCount++;
            }
        }
        return compactTokenCount >= Math.max(3, tokens.length - 1);
    }

    private int countAlignedWhitespaceLines(String nativeText) {
        if (!StringUtils.hasText(nativeText)) {
            return 0;
        }
        int alignedLines = 0;
        for (String line : nativeText.split("\\R")) {
            if (!StringUtils.hasText(line) || line.length() < 16) {
                continue;
            }
            java.util.regex.Matcher matcher = ALIGNED_WHITESPACE_PATTERN.matcher(line);
            int whitespaceRuns = 0;
            while (matcher.find()) {
                whitespaceRuns++;
                if (whitespaceRuns >= 2) {
                    alignedLines++;
                    break;
                }
            }
        }
        return alignedLines;
    }

    enum PageRoute {
        FAST_TRACK,
        VISUAL_TRACK
    }

    record PageRoutingDecision(PageRoute route, String reason, int alignedWhitespaceLines) {
        boolean isVisualTrack() {
            return route == PageRoute.VISUAL_TRACK;
        }
    }
}
