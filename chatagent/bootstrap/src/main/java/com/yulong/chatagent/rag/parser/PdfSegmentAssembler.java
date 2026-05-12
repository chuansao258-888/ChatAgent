package com.yulong.chatagent.rag.parser;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles page segments from native and visual-track results while keeping
 * the existing fallback order (VDP -> native text -> empty page).
 */
final class PdfSegmentAssembler {

    private final PdfPageTextExtractor textExtractor;

    PdfSegmentAssembler(PdfPageTextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    SegmentAssemblyResult assemble(List<String> cleanedPageTexts,
                                   List<PdfPageTextExtractor.PageExtractionSnapshot> pageSnapshots,
                                   List<PdfQualityRouter.PageRoutingDecision> routingDecisions,
                                   Map<Integer, VdpPageResult> visualResults) {
        List<ParseSegment> segments = new ArrayList<>(cleanedPageTexts.size());
        int totalChars = 0;
        int visualTrackPageCount = 0;
        int visualSuccessPageCount = 0;
        int visualDegradedPageCount = 0;
        int visualFailedPageCount = 0;

        for (int page = 1; page <= cleanedPageTexts.size(); page++) {
            String pageText = cleanedPageTexts.get(page - 1);
            PdfPageTextExtractor.PageExtractionSnapshot pageSnapshot = pageSnapshots.get(page - 1);
            PdfPageTextExtractor.PageFontProfile pageFontProfile = pageSnapshot.fontProfile();
            PdfPageTextExtractor.PageStructuredText structuredPageText = textExtractor.restoreStructuredNativeMarkdown(pageSnapshot, pageText);
            PdfQualityRouter.PageRoutingDecision routingDecision = routingDecisions.get(page - 1);
            ParseSegment segment;
            if (routingDecision.isVisualTrack()) {
                visualTrackPageCount++;
                VdpPageResult visualResult = visualResults.getOrDefault(
                        page - 1,
                        PdfVdpDispatcher.failedPageResult(page - 1, "Visual page result missing after dispatch")
                );
                if (visualResult.status() == VdpPageStatus.SUCCESS) {
                    visualSuccessPageCount++;
                } else if (visualResult.status() == VdpPageStatus.FAILED) {
                    visualFailedPageCount++;
                } else {
                    visualDegradedPageCount++;
                }
                segment = buildVisualSegment(page - 1, pageText, structuredPageText, pageFontProfile, routingDecision, visualResult);
            } else {
                segment = buildNativePageSegment(page - 1, pageText, structuredPageText, pageFontProfile, routingDecision);
            }
            totalChars += segment.charCount();
            segments.add(segment);
        }

        return new SegmentAssemblyResult(
                segments,
                totalChars,
                visualTrackPageCount,
                visualSuccessPageCount,
                visualDegradedPageCount,
                visualFailedPageCount
        );
    }

    private ParseSegment buildNativePageSegment(int pageIndex,
                                                String pageText,
                                                PdfPageTextExtractor.PageStructuredText structuredPageText,
                                                PdfPageTextExtractor.PageFontProfile pageFontProfile,
                                                PdfQualityRouter.PageRoutingDecision routingDecision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pageNumber", pageIndex + 1);
        metadata.put("pageRoute", routingDecision.route().name());
        metadata.put("pageRouteReason", routingDecision.reason());
        metadata.put("alignedWhitespaceLines", routingDecision.alignedWhitespaceLines());
        metadata.put("nativeCharCount", pageText.length());
        metadata.put("contentOrigin", "NATIVE");
        attachFontMetadata(metadata, pageFontProfile);
        attachStructureMetadata(metadata, structuredPageText);
        return new ParseSegment(structuredPageText.text(), pageIndex, SegmentType.PAGE, metadata);
    }

    private ParseSegment buildVisualSegment(int pageIndex,
                                            String nativeText,
                                            PdfPageTextExtractor.PageStructuredText structuredPageText,
                                            PdfPageTextExtractor.PageFontProfile pageFontProfile,
                                            PdfQualityRouter.PageRoutingDecision routingDecision,
                                            VdpPageResult visualResult) {
        String markdown = TextCleanupUtil.cleanup(visualResult.markdown());
        Map<String, Object> metadata = new LinkedHashMap<>(visualResult.metadata());
        metadata.put("pageNumber", pageIndex + 1);
        metadata.put("pageRoute", routingDecision.route().name());
        metadata.put("pageRouteReason", routingDecision.reason());
        metadata.put("alignedWhitespaceLines", routingDecision.alignedWhitespaceLines());
        metadata.put("nativeCharCount", nativeText.length());
        metadata.put("vdpStatus", visualResult.status().name());
        metadata.putIfAbsent("visualType", "IMAGE");
        metadata.putIfAbsent("contentOrigin", "VDP_TRANSCRIBED");
        attachFontMetadata(metadata, pageFontProfile);
        if (visualResult.status() != VdpPageStatus.SUCCESS) {
            metadata.put("degraded", true);
        }

        if (StringUtils.hasText(markdown)) {
            markMarkdownContent(metadata);
            return new ParseSegment(markdown, pageIndex, SegmentType.PAGE, metadata);
        }
        if (StringUtils.hasText(nativeText)) {
            metadata.put("contentOrigin", "NATIVE");
            metadata.put("visualFallback", "NATIVE_TEXT");
            metadata.put("degraded", true);
            attachStructureMetadata(metadata, structuredPageText);
            return new ParseSegment(structuredPageText.text(), pageIndex, SegmentType.PAGE, metadata);
        }
        metadata.put("visualFallback", "EMPTY_PAGE");
        metadata.put("degraded", true);
        metadata.putIfAbsent("interpretiveNote", "[此页内容解析失败]");
        return new ParseSegment("", pageIndex, SegmentType.PAGE, metadata);
    }

    private void attachFontMetadata(Map<String, Object> metadata, PdfPageTextExtractor.PageFontProfile pageFontProfile) {
        if (metadata == null || pageFontProfile == null || pageFontProfile.sampleCount() <= 0) {
            return;
        }
        metadata.put("dominantFontSizePt", pageFontProfile.dominantFontSizePt());
        metadata.put("maxFontSizePt", pageFontProfile.maxFontSizePt());
        metadata.put("minFontSizePt", pageFontProfile.minFontSizePt());
        metadata.put("fontSampleCount", pageFontProfile.sampleCount());
        metadata.put("headingLikePage", textExtractor.isHeadingLikePage(pageFontProfile));
    }

    private void attachStructureMetadata(Map<String, Object> metadata,
                                         PdfPageTextExtractor.PageStructuredText structuredPageText) {
        if (metadata == null || structuredPageText == null) {
            return;
        }
        metadata.put("fontAwareStructureRestored", structuredPageText.restored());
        if (structuredPageText.restored()) {
            markMarkdownContent(metadata);
        }
        if (structuredPageText.headingCount() > 0) {
            metadata.put("restoredHeadingCount", structuredPageText.headingCount());
        }
    }

    private void markMarkdownContent(Map<String, Object> metadata) {
        if (metadata != null) {
            metadata.put("contentFormat", "MARKDOWN");
        }
    }

    record SegmentAssemblyResult(List<ParseSegment> segments,
                                 int totalChars,
                                 int visualTrackPageCount,
                                 int visualSuccessPageCount,
                                 int visualDegradedPageCount,
                                 int visualFailedPageCount) {
    }
}
