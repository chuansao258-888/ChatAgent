package com.yulong.chatagent.rag.parser;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Extracts text and font metadata from PDF pages for routing decisions
 * and structured markdown restoration.
 */
class PdfPageTextExtractor {

    private static final double HEADING_FONT_DELTA_PT = 1.5d;

    List<PageExtractionSnapshot> extractPageSnapshots(org.apache.pdfbox.pdmodel.PDDocument document) throws IOException {
        PageCollectingTextStripper stripper = new PageCollectingTextStripper();
        stripper.writeText(document, new PageCaptureWriter(stripper));
        return stripper.pageSnapshots();
    }

    List<PageExtractionSnapshot> alignPageSnapshots(List<PageExtractionSnapshot> extractedPageSnapshots, int pageCount) {
        if (pageCount <= 0) {
            return List.of();
        }
        List<PageExtractionSnapshot> alignedPageSnapshots = new ArrayList<>(pageCount);
        if (extractedPageSnapshots != null && !extractedPageSnapshots.isEmpty()) {
            alignedPageSnapshots.addAll(extractedPageSnapshots.subList(0, Math.min(extractedPageSnapshots.size(), pageCount)));
        }
        while (alignedPageSnapshots.size() < pageCount) {
            alignedPageSnapshots.add(PageExtractionSnapshot.empty());
        }
        return alignedPageSnapshots;
    }

    PageStructuredText restoreStructuredNativeMarkdown(PageExtractionSnapshot pageSnapshot, String fallbackText) {
        if (pageSnapshot == null || !StringUtils.hasText(fallbackText) || !isHeadingLikePage(pageSnapshot.fontProfile())) {
            return PageStructuredText.plain(fallbackText);
        }
        List<PageLineSnapshot> lineSnapshots = pageSnapshot.lineSnapshots();
        if (lineSnapshots == null || lineSnapshots.isEmpty()) {
            return PageStructuredText.plain(fallbackText);
        }

        List<String> restoredLines = new ArrayList<>(lineSnapshots.size() * 2);
        int headingCount = 0;
        for (int lineIndex = 0; lineIndex < lineSnapshots.size(); lineIndex++) {
            PageLineSnapshot lineSnapshot = lineSnapshots.get(lineIndex);
            String lineText = TextCleanupUtil.cleanup(lineSnapshot.text(), true, true, false, 1);
            if (!StringUtils.hasText(lineText)) {
                if (!restoredLines.isEmpty() && !restoredLines.get(restoredLines.size() - 1).isEmpty()) {
                    restoredLines.add("");
                }
                continue;
            }
            Integer headingLevel = resolveHeadingLevel(lineIndex, lineText, lineSnapshot.fontProfile(), pageSnapshot.fontProfile());
            if (headingLevel != null) {
                if (!restoredLines.isEmpty() && !restoredLines.get(restoredLines.size() - 1).isEmpty()) {
                    restoredLines.add("");
                }
                restoredLines.add("#".repeat(headingLevel) + " " + lineText);
                restoredLines.add("");
                headingCount++;
                continue;
            }
            restoredLines.add(lineText);
        }

        String restoredText = TextCleanupUtil.cleanup(String.join("\n", restoredLines));
        if (headingCount <= 0 || !StringUtils.hasText(restoredText)) {
            return PageStructuredText.plain(fallbackText);
        }
        return new PageStructuredText(restoredText, true, headingCount);
    }

    boolean isHeadingLikePage(PageFontProfile pageFontProfile) {
        return pageFontProfile != null
                && pageFontProfile.sampleCount() > 0
                && pageFontProfile.maxFontSizePt() >= 14.0d
                && pageFontProfile.maxFontSizePt() - pageFontProfile.dominantFontSizePt() >= 2.0d;
    }

    private Integer resolveHeadingLevel(int lineIndex, String lineText,
                                        PageFontProfile lineFontProfile, PageFontProfile pageFontProfile) {
        if (lineFontProfile == null || pageFontProfile == null || lineFontProfile.sampleCount() <= 0) {
            return null;
        }
        if (!looksLikeHeadingLine(lineText)) {
            return null;
        }
        double lineMax = lineFontProfile.maxFontSizePt();
        double lineDominant = lineFontProfile.dominantFontSizePt();
        double pageDominant = pageFontProfile.dominantFontSizePt();
        double pageMax = pageFontProfile.maxFontSizePt();
        boolean visuallyProminent = lineMax >= pageDominant + HEADING_FONT_DELTA_PT
                || lineDominant >= pageDominant + HEADING_FONT_DELTA_PT;
        if (!visuallyProminent) {
            return null;
        }
        if (lineIndex == 0 && lineMax >= pageMax - 0.1d && lineMax >= pageDominant + 2.0d) {
            return 2;
        }
        if (lineMax >= pageDominant + 2.5d || lineDominant >= pageDominant + 2.0d) {
            return 2;
        }
        return 3;
    }

    private boolean looksLikeHeadingLine(String lineText) {
        String normalized = lineText == null ? "" : lineText.replaceAll("\\s+", " ");
        if (!StringUtils.hasText(normalized) || normalized.startsWith("#")) {
            return false;
        }
        if (normalized.length() > 120) {
            return false;
        }
        if (normalized.endsWith(".") || normalized.endsWith("。")) {
            return false;
        }
        Pattern punctuation = Pattern.compile("[。！？；;.!?]");
        if (punctuation.matcher(normalized).find()) {
            return false;
        }
        String[] tokens = normalized.split(" ");
        return tokens.length <= 14;
    }

    // ---- inner types (moved from PdfDocumentParser) ----

    static final class PageCollectingTextStripper extends PDFTextStripper {
        private final List<PageExtractionSnapshot> pageSnapshots = new ArrayList<>();
        private StringBuilder currentPageText;
        private PageFontAccumulator currentPageFontAccumulator;
        private List<PageLineSnapshot> currentPageLines;
        private StringBuilder currentLineText;
        private PageFontAccumulator currentLineFontAccumulator;

        PageCollectingTextStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPageText = new StringBuilder();
            currentPageFontAccumulator = new PageFontAccumulator();
            currentPageLines = new ArrayList<>();
            currentLineText = new StringBuilder();
            currentLineFontAccumulator = new PageFontAccumulator();
            super.startPage(page);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            flushCurrentLine();
            pageSnapshots.add(new PageExtractionSnapshot(
                    currentPageText == null ? "" : currentPageText.toString(),
                    currentPageFontAccumulator == null ? PageFontProfile.empty() : currentPageFontAccumulator.toProfile(),
                    currentPageLines == null ? List.of() : List.copyOf(currentPageLines)
            ));
            currentPageText = null;
            currentPageFontAccumulator = null;
            currentPageLines = null;
            currentLineText = null;
            currentLineFontAccumulator = null;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (currentPageFontAccumulator != null && text != null) {
                currentPageFontAccumulator.record(text.getFontSizeInPt());
            }
            super.processTextPosition(text);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (currentLineText != null && text != null) {
                currentLineText.append(text);
            }
            if (currentLineFontAccumulator != null && textPositions != null) {
                for (TextPosition textPosition : textPositions) {
                    if (textPosition != null) {
                        currentLineFontAccumulator.record(textPosition.getFontSizeInPt());
                    }
                }
            }
            super.writeString(text, textPositions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushCurrentLine();
            super.writeLineSeparator();
        }

        private void append(char[] cbuf, int off, int len) {
            if (currentPageText != null && len > 0) {
                currentPageText.append(cbuf, off, len);
            }
        }

        private void flushCurrentLine() {
            if (currentPageLines == null || currentLineText == null || currentLineFontAccumulator == null) {
                return;
            }
            String lineText = TextCleanupUtil.cleanup(currentLineText.toString(), true, true, false, 1);
            if (StringUtils.hasText(lineText)) {
                currentPageLines.add(new PageLineSnapshot(lineText, currentLineFontAccumulator.toProfile()));
            }
            currentLineText = new StringBuilder();
            currentLineFontAccumulator = new PageFontAccumulator();
        }

        List<PageExtractionSnapshot> pageSnapshots() {
            return List.copyOf(pageSnapshots);
        }
    }

    static final class PageCaptureWriter extends Writer {
        private final PageCollectingTextStripper stripper;

        PageCaptureWriter(PageCollectingTextStripper stripper) {
            this.stripper = stripper;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            stripper.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }

    record PageExtractionSnapshot(String text, PageFontProfile fontProfile, List<PageLineSnapshot> lineSnapshots) {
        static PageExtractionSnapshot empty() {
            return new PageExtractionSnapshot("", PageFontProfile.empty(), List.of());
        }
    }

    record PageLineSnapshot(String text, PageFontProfile fontProfile) {
    }

    record PageStructuredText(String text, boolean restored, int headingCount) {
        static PageStructuredText plain(String text) {
            return new PageStructuredText(text == null ? "" : text, false, 0);
        }
    }

    record PageFontProfile(double dominantFontSizePt, double maxFontSizePt, double minFontSizePt, int sampleCount) {
        static PageFontProfile empty() {
            return new PageFontProfile(0d, 0d, 0d, 0);
        }
    }

    static final class PageFontAccumulator {
        private final Map<Integer, Integer> fontSizeBuckets = new HashMap<>();
        private double maxFontSizePt;
        private double minFontSizePt = Double.MAX_VALUE;
        private int sampleCount;

        void record(float fontSizePt) {
            if (fontSizePt <= 0f || Float.isNaN(fontSizePt) || Float.isInfinite(fontSizePt)) {
                return;
            }
            sampleCount++;
            double normalized = Math.round(fontSizePt * 10.0d) / 10.0d;
            maxFontSizePt = Math.max(maxFontSizePt, normalized);
            minFontSizePt = Math.min(minFontSizePt, normalized);
            int bucket = (int) Math.round(normalized * 10.0d);
            fontSizeBuckets.merge(bucket, 1, Integer::sum);
        }

        PageFontProfile toProfile() {
            if (sampleCount <= 0 || fontSizeBuckets.isEmpty()) {
                return PageFontProfile.empty();
            }
            int dominantBucket = 0;
            int dominantCount = -1;
            for (Map.Entry<Integer, Integer> entry : fontSizeBuckets.entrySet()) {
                if (entry.getValue() > dominantCount) {
                    dominantBucket = entry.getKey();
                    dominantCount = entry.getValue();
                }
            }
            return new PageFontProfile(
                    dominantBucket / 10.0d,
                    maxFontSizePt,
                    minFontSizePt == Double.MAX_VALUE ? 0d : minFontSizePt,
                    sampleCount
            );
        }
    }
}
