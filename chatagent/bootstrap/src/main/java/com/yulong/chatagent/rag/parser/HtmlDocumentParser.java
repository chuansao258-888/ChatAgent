package com.yulong.chatagent.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Structure-aware parser for HTML sources.
 * Converts HTML DOM to semantic Markdown via jsoup, emitting a single FULL segment
 * with contentFormat=MARKDOWN so that SegmentAwareChunkerRouter routes to
 * StructureAwareMarkdownChunker.
 * <p>
 * Falls back to TikaDocumentParser when jsoup output is empty or near-empty,
 * and returns QualityLevel.REJECTED when no usable text remains.
 */
@Slf4j
@Component
public class HtmlDocumentParser implements DocumentParser {

    private static final int NEAR_EMPTY_THRESHOLD = 100;
    private static final int NEAR_EMPTY_SIZE_BYTES = 1024;

    private final TikaDocumentParser tikaFallback;

    public HtmlDocumentParser(TikaDocumentParser tikaFallback) {
        this.tikaFallback = tikaFallback;
    }

    @Override
    public String getParserType() {
        return ParserType.HTML.getType();
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && !type.rejected() && type.isHtml();
    }

    @Override
    @Deprecated(forRemoval = true)
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        byte[] sourceBytes = content == null ? new byte[0] : content;
        return parse(() -> new ByteArrayInputStream(sourceBytes), mimeType, options);
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        // Read full source bytes to determine size and support re-parsing for fallback.
        byte[] sourceBytes = readAllBytes(streamSupplier);
        int sourceLength = sourceBytes.length;

        // Known zero-byte → REJECTED directly.
        if (sourceLength == 0) {
            return rejected("Empty HTML source (0 bytes)");
        }

        // Primary jsoup parse.
        String html = new String(sourceBytes, StandardCharsets.UTF_8);
        Document doc;
        try {
            doc = Jsoup.parse(html);
        } catch (Exception e) {
            log.warn("jsoup parse failed, attempting Tika fallback: {}", e.getMessage());
            return attemptTikaFallback(sourceBytes, mimeType, options, sourceLength,
                    "jsoup parse failed: " + e.getMessage());
        }

        // Strip noise elements before conversion.
        doc.select("script, style, nav, footer").remove();

        // Convert to Markdown.
        String markdown = convertToMarkdown(doc);
        String cleaned = TextCleanupUtil.cleanup(markdown);

        // Evaluate jsoup output quality.
        if (cleaned.isEmpty()) {
            log.info("jsoup produced empty output for HTML source ({} bytes), attempting Tika fallback", sourceLength);
            return attemptTikaFallback(sourceBytes, mimeType, options, sourceLength,
                    "jsoup output empty");
        }

        if (cleaned.length() < NEAR_EMPTY_THRESHOLD && sourceLength > NEAR_EMPTY_SIZE_BYTES) {
            log.info("jsoup produced near-empty output ({} chars) for large HTML source ({} bytes), attempting Tika fallback",
                    cleaned.length(), sourceLength);
            return attemptTikaFallback(sourceBytes, mimeType, options, sourceLength,
                    "jsoup output near-empty (" + cleaned.length() + " chars) for " + sourceLength + " byte source");
        }

        // Valid jsoup output — emit primary result.
        return buildResult(cleaned, sourceLength, false, null);
    }

    private ParseResult attemptTikaFallback(byte[] sourceBytes,
                                            String mimeType,
                                            Map<String, Object> options,
                                            int sourceLength,
                                            String reason) {
        try {
            // Create a fresh supplier from the already-read bytes.
            Supplier<InputStream> fallbackSupplier = () -> new ByteArrayInputStream(sourceBytes);
            ParseResult tikaResult = tikaFallback.parse(fallbackSupplier, mimeType, options);
            String tikaText = tikaResult.getSegments().isEmpty()
                    ? ""
                    : tikaResult.getSegments().get(0).text();

            if (tikaText.isEmpty()) {
                return rejected("Both jsoup and Tika produced no usable text (" + reason + ")");
            }

            log.info("Tika fallback succeeded for HTML source ({} bytes): {} chars extracted",
                    sourceLength, tikaText.length());

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("contentFormat", "PLAIN");
            metadata.put("parserType", ParserType.TIKA.getType());
            metadata.put("htmlSourceLength", sourceLength);

            return ParseResult.builder()
                    .segments(List.of(new ParseSegment(tikaText, 0, SegmentType.FULL, metadata)))
                    .parserType(ParserType.TIKA.getType())
                    .qualityLevel(tikaResult.getQualityLevel())
                    .diagnostics(Map.of("htmlFallback", true, "fallbackReason", reason))
                    .warnings(List.of("HTML parsing fell back to Tika: " + reason))
                    .build();
        } catch (Exception e) {
            return rejected("Both jsoup and Tika failed (jsoup: " + reason + ", Tika: " + e.getMessage() + ")");
        }
    }

    private ParseResult buildResult(String cleaned, int sourceLength, boolean fallback, String fallbackReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contentFormat", "MARKDOWN");
        metadata.put("parserType", "html-jsoup");
        metadata.put("htmlSourceLength", sourceLength);

        ParseResult.ParseResultBuilder builder = ParseResult.builder()
                .segments(List.of(new ParseSegment(cleaned, 0, SegmentType.FULL, metadata)))
                .parserType(ParserType.HTML.getType());

        if (fallback && fallbackReason != null) {
            builder.diagnostics(Map.of("htmlFallback", true, "fallbackReason", fallbackReason));
            builder.warnings(List.of("HTML parsing fell back to Tika: " + fallbackReason));
        }

        return builder.build();
    }

    private ParseResult rejected(String reason) {
        log.warn("HTML parsing rejected: {}", reason);
        return ParseResult.builder()
                .segments(List.of())
                .parserType(ParserType.HTML.getType())
                .qualityLevel(QualityLevel.REJECTED)
                .warnings(List.of(reason))
                .build();
    }

    // -------------------------------------------------------------------------
    // HTML → Markdown conversion
    // -------------------------------------------------------------------------

    String convertToMarkdown(Document doc) {
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new MarkdownVisitor(sb), doc.body());
        return sb.toString();
    }

    private static class MarkdownVisitor implements NodeVisitor {

        private static final java.util.Set<String> NOISE_TAGS = java.util.Set.of(
                "script", "style", "nav", "footer", "head", "#declaration", "#comment"
        );

        private final StringBuilder sb;
        private boolean atLineStart = true;

        MarkdownVisitor(StringBuilder sb) {
            this.sb = sb;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof Element el) {
                String tag = el.tagName().toLowerCase();

                // Skip noise elements entirely. They are already removed from the DOM,
                // but this guards against any that slip through.
                if (NOISE_TAGS.contains(tag)) {
                    return;
                }

                switch (tag) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        ensureParagraphBreak();
                        int level = tag.charAt(1) - '0';
                        sb.append("#".repeat(level)).append(' ');
                    }
                    case "p", "div", "section", "article", "main", "aside", "header" ->
                            ensureParagraphBreak();
                    case "li" -> {
                        ensureLineBreak();
                        // Determine if parent is ol or ul
                        Element parent = el.parent();
                        if (parent != null && "ol".equals(parent.tagName().toLowerCase())) {
                            int index = parent.children().indexOf(el) + 1;
                            sb.append(index).append(". ");
                        } else {
                            sb.append("- ");
                        }
                    }
                    case "br" -> sb.append("\n");
                    case "strong", "b" -> sb.append("**");
                    case "em", "i" -> sb.append("*");
                    case "code" -> {
                        if (el.parent() != null && "pre".equals(el.parent().tagName().toLowerCase())) {
                            // Code inside <pre> is handled by <pre> block.
                        } else {
                            sb.append("`");
                        }
                    }
                    case "pre" -> {
                        ensureParagraphBreak();
                        sb.append("```\n");
                    }
                    case "blockquote" -> {
                        ensureParagraphBreak();
                        sb.append("> ");
                    }
                    case "hr" -> {
                        ensureParagraphBreak();
                        sb.append("---");
                        ensureParagraphBreak();
                    }
                    case "table" -> {
                        ensureParagraphBreak();
                        renderTable(el, sb);
                        // Signal that table was already rendered so we skip child traversal.
                        el.attr("_rendered", "true");
                    }
                    case "a" -> {
                        String href = el.attr("href");
                        if (!href.isEmpty()) {
                            sb.append("[");
                        }
                    }
                    case "img" -> {
                        String alt = el.attr("alt");
                        String src = el.attr("src");
                        sb.append("![").append(alt).append("](").append(src).append(")");
                    }
                    case "tr", "td", "th", "thead", "tbody", "tfoot" -> {
                        // Table sub-elements are handled by renderTable().
                    }
                    // Inline XBRL taxonomy elements: strip tags, preserve text content.
                    default -> {
                        // No special handling — text content is preserved via TextNode traversal.
                    }
                }
            } else if (node instanceof TextNode textNode) {
                String text = textNode.text();
                if (text.isEmpty()) {
                    return;
                }
                // If inside a table that was rendered, skip the text.
                Node parentNode = textNode.parent();
                Element parentEl = (parentNode instanceof Element) ? (Element) parentNode : null;
                if (parentEl != null && isInsideRenderedTable(parentEl)) {
                    return;
                }
                sb.append(text);
                atLineStart = false;
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (node instanceof Element el) {
                String tag = el.tagName().toLowerCase();

                if (NOISE_TAGS.contains(tag)) {
                    return;
                }

                switch (tag) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        sb.append("\n\n");
                        atLineStart = true;
                    }
                    case "p" -> {
                        sb.append("\n\n");
                        atLineStart = true;
                    }
                    case "div", "section", "article", "main", "aside", "header" -> {
                        sb.append("\n\n");
                        atLineStart = true;
                    }
                    case "li" -> {
                        sb.append("\n");
                        atLineStart = true;
                    }
                    case "ul", "ol" -> {
                        sb.append("\n");
                        atLineStart = true;
                    }
                    case "strong", "b" -> sb.append("**");
                    case "em", "i" -> sb.append("*");
                    case "code" -> {
                        if (el.parent() != null && "pre".equals(el.parent().tagName().toLowerCase())) {
                            // Handled by <pre> block.
                        } else {
                            sb.append("`");
                        }
                    }
                    case "pre" -> {
                        sb.append("\n```\n\n");
                        atLineStart = true;
                    }
                    case "blockquote" -> {
                        sb.append("\n\n");
                        atLineStart = true;
                    }
                    case "a" -> {
                        String href = el.attr("href");
                        if (!href.isEmpty()) {
                            sb.append("](").append(href).append(")");
                        }
                    }
                    case "table" -> {
                        // Already rendered in head(); just clean up marker.
                        el.removeAttr("_rendered");
                        sb.append("\n\n");
                        atLineStart = true;
                    }
                    case "tr", "td", "th", "thead", "tbody", "tfoot" -> {
                        // Handled by renderTable().
                    }
                    default -> {
                        // Generic block-level elements get a paragraph break.
                        if (isBlockElement(tag)) {
                            sb.append("\n\n");
                            atLineStart = true;
                        }
                    }
                }
            }
        }

        private boolean isInsideRenderedTable(Element el) {
            Element current = el;
            while (current != null) {
                if ("table".equals(current.tagName().toLowerCase()) && current.hasAttr("_rendered")) {
                    return true;
                }
                current = current.parent();
            }
            return false;
        }

        private void ensureParagraphBreak() {
            if (sb.isEmpty()) {
                return;
            }
            if (atLineStart && sb.length() >= 2 && sb.charAt(sb.length() - 2) == '\n') {
                return;
            }
            if (!atLineStart || sb.charAt(sb.length() - 1) != '\n') {
                sb.append("\n\n");
            } else {
                sb.append("\n");
            }
            atLineStart = true;
        }

        private void ensureLineBreak() {
            if (sb.isEmpty() || atLineStart) {
                return;
            }
            if (sb.charAt(sb.length() - 1) != '\n') {
                sb.append("\n");
            }
            atLineStart = true;
        }

        private boolean isBlockElement(String tag) {
            return java.util.Set.of("address", "fieldset", "form", "dl", "dd", "dt",
                    "figcaption", "figure", "details", "summary", "hgroup").contains(tag);
        }
    }

    // -------------------------------------------------------------------------
    // Table rendering — rectangular pipe-delimited Markdown tables
    // -------------------------------------------------------------------------

    private static void renderTable(Element table, StringBuilder sb) {
        // Collect all direct rows (ignoring thead/tbody/tfoot wrappers).
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            String childTag = child.tagName().toLowerCase();
            if ("tr".equals(childTag)) {
                rows.add(child);
            } else if ("thead".equals(childTag) || "tbody".equals(childTag) || "tfoot".equals(childTag)) {
                for (Element tr : child.children()) {
                    if ("tr".equals(tr.tagName().toLowerCase())) {
                        rows.add(tr);
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            return;
        }

        // Determine the maximum logical column count across all rows,
        // accounting for colspan on direct <td>/<th> children only.
        int maxCols = 0;
        List<List<CellInfo>> rowCells = new ArrayList<>();
        // Track rowspan coverage: map from column index to remaining span count.
        List<Integer> rowspanCoverage = new ArrayList<>();

        for (Element row : rows) {
            List<CellInfo> cells = new ArrayList<>();
            int colIndex = 0;

            // Advance past active rowspan coverage.
            // rowspanCoverage grows as needed.

            for (Element cell : row.children()) {
                String cellTag = cell.tagName().toLowerCase();
                if (!"td".equals(cellTag) && !"th".equals(cellTag)) {
                    continue;
                }

                // Advance past active rowspan placeholders at this column.
                while (colIndex < rowspanCoverage.size() && rowspanCoverage.get(colIndex) > 0) {
                    cells.add(CellInfo.placeholder()); // rowspan placeholder
                    rowspanCoverage.set(colIndex, rowspanCoverage.get(colIndex) - 1);
                    colIndex++;
                }

                int colspan = Math.max(1, parseAttrInt(cell, "colspan", 1));
                int rowspan = parseAttrInt(cell, "rowspan", 1);
                String text = extractCellText(cell);

                // Emit text once in the first column, then placeholders for remaining colspan.
                cells.add(CellInfo.of(text, "th".equals(cellTag)));

                // Ensure rowspanCoverage list is large enough.
                while (rowspanCoverage.size() <= colIndex) {
                    rowspanCoverage.add(0);
                }
                if (rowspan > 1) {
                    rowspanCoverage.set(colIndex, rowspan - 1);
                }
                colIndex++;

                for (int i = 1; i < colspan; i++) {
                    cells.add(CellInfo.placeholder());
                    while (rowspanCoverage.size() <= colIndex) {
                        rowspanCoverage.add(0);
                    }
                    if (rowspan > 1) {
                        rowspanCoverage.set(colIndex, rowspan - 1);
                    }
                    colIndex++;
                }
            }

            // Fill remaining rowspan placeholders at end of row.
            while (colIndex < rowspanCoverage.size() && rowspanCoverage.get(colIndex) > 0) {
                cells.add(CellInfo.placeholder());
                rowspanCoverage.set(colIndex, rowspanCoverage.get(colIndex) - 1);
                colIndex++;
            }

            maxCols = Math.max(maxCols, cells.size());
            rowCells.add(cells);
        }

        // Pad all rows to maxCols.
        for (List<CellInfo> cells : rowCells) {
            while (cells.size() < maxCols) {
                cells.add(CellInfo.empty());
            }
        }

        // Determine if first row contains <th> cells.
        boolean hasHeader = !rowCells.isEmpty() && rowCells.get(0).stream().anyMatch(c -> c.isHeader);

        // Render header row.
        List<CellInfo> headerRow = hasHeader ? rowCells.get(0) : synthesizeHeader(maxCols);
        appendRow(headerRow, maxCols, sb);
        sb.append("\n|");
        for (int i = 0; i < maxCols; i++) {
            sb.append(" --- |");
        }

        // Render data rows.
        int dataStart = hasHeader ? 1 : 0;
        for (int r = dataStart; r < rowCells.size(); r++) {
            sb.append("\n");
            appendRow(rowCells.get(r), maxCols, sb);
        }
    }

    private static List<CellInfo> synthesizeHeader(int cols) {
        List<CellInfo> header = new ArrayList<>();
        for (int i = 0; i < cols; i++) {
            header.add(CellInfo.of("Column " + (i + 1), true));
        }
        return header;
    }

    private static void appendRow(List<CellInfo> cells, int maxCols, StringBuilder sb) {
        sb.append("|");
        for (int i = 0; i < maxCols; i++) {
            CellInfo cell = i < cells.size() ? cells.get(i) : CellInfo.empty();
            sb.append(" ").append(cell.text).append(" |");
        }
    }

    /**
     * Extracts visible text from a table cell, collapsing nested table text
     * into the owning cell without emitting nested rows a second time.
     */
    private static String extractCellText(Element cell) {
        // For cells containing nested tables, extract text without the nested table's
        // structural elements (those are intentionally collapsed into this cell).
        StringBuilder cellSb = new StringBuilder();
        extractTextRecursive(cell, cellSb);
        String text = cellSb.toString().replace("|", "\\|").replace("\n", " ").trim();
        return text;
    }

    private static void extractTextRecursive(Element element, StringBuilder sb) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode tn) {
                String t = tn.text();
                if (!t.isEmpty()) {
                    if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') {
                        sb.append(' ');
                    }
                    sb.append(t);
                }
            } else if (child instanceof Element el) {
                String tag = el.tagName().toLowerCase();
                // Nested table: emit text content but not the table structure.
                if ("table".equals(tag)) {
                    extractTextRecursive(el, sb);
                } else if ("script".equals(tag) || "style".equals(tag)) {
                    // Skip noise.
                } else if ("br".equals(tag)) {
                    sb.append(' ');
                } else if ("strong".equals(tag) || "b".equals(tag)) {
                    sb.append("**");
                    extractTextRecursive(el, sb);
                    sb.append("**");
                } else if ("em".equals(tag) || "i".equals(tag)) {
                    sb.append("*");
                    extractTextRecursive(el, sb);
                    sb.append("*");
                } else {
                    extractTextRecursive(el, sb);
                }
            }
        }
    }

    private static int parseAttrInt(Element el, String attr, int defaultVal) {
        try {
            String val = el.attr(attr);
            if (val == null || val.isEmpty()) {
                return defaultVal;
            }
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static byte[] readAllBytes(Supplier<InputStream> streamSupplier) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                return new byte[0];
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = stream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, n);
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to read HTML source stream: {}", e.getMessage());
            return new byte[0];
        }
    }

    private record CellInfo(String text, boolean isHeader, boolean isPlaceholder) {
        static CellInfo of(String text, boolean isHeader) {
            return new CellInfo(text, isHeader, false);
        }

        static CellInfo placeholder() {
            return new CellInfo("", false, true);
        }

        static CellInfo empty() {
            return new CellInfo("", false, false);
        }
    }
}
