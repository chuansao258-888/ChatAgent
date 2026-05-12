package com.yulong.chatagent.rag.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Structure-aware parser for Word .docx files.
 * Converts headings, paragraphs, lists, and tables to Markdown,
 * then emits a single FULL segment with contentFormat=MARKDOWN.
 */
@Component
public class WordDocumentParser implements DocumentParser {

    @Override
    public String getParserType() {
        return ParserType.WORD.getType();
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                return ParseResult.ofText("");
            }
            XWPFDocument document = new XWPFDocument(stream);
            String markdown = convertToMarkdown(document);
            document.close();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("docType", "word");
            metadata.put("sourceFormat", "docx");
            metadata.put("parserType", "word-poi");
            metadata.put("contentFormat", "MARKDOWN");

            return ParseResult.builder()
                    .segments(List.of(new ParseSegment(markdown, 0, SegmentType.FULL, metadata)))
                    .parserType(ParserType.WORD.getType())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Word document", e);
        }
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && !type.rejected() && type.isWord();
    }

    String convertToMarkdown(XWPFDocument document) {
        StringBuilder sb = new StringBuilder();
        boolean firstBlock = true;

        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                String block = convertParagraph(paragraph);
                if (block == null || block.isEmpty()) {
                    continue;
                }
                if (!firstBlock) {
                    sb.append("\n\n");
                }
                sb.append(block);
                firstBlock = false;
            } else if (element instanceof XWPFTable table) {
                if (!firstBlock) {
                    sb.append("\n\n");
                }
                sb.append(convertTable(table));
                firstBlock = false;
            }
        }
        return sb.toString();
    }

    private String convertParagraph(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        String text = extractParagraphText(paragraph);
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();

        if (style != null) {
            String normalizedStyle = style.replaceAll("\\s+", "").toLowerCase();
            int level = headingLevel(normalizedStyle);
            if (level > 0) {
                return "#".repeat(level) + " " + text;
            }
        }

        String numFmt = paragraph.getNumFmt();
        if (numFmt != null) {
            boolean isOrdered = "decimal".equals(numFmt) || numFmt.startsWith("decimal");
            String bulletPrefix = isOrdered ? "1. " : "- ";
            return bulletPrefix + text;
        }

        return text;
    }

    private int headingLevel(String normalizedStyle) {
        if ("title".equals(normalizedStyle)) {
            return 1;
        }
        if ("subtitle".equals(normalizedStyle)) {
            return 2;
        }
        for (int i = 1; i <= 6; i++) {
            if (normalizedStyle.equals("heading" + i)) {
                return i;
            }
        }
        return 0;
    }

    private String extractParagraphText(XWPFParagraph paragraph) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText != null) {
                sb.append(runText);
            }
        }
        return sb.toString();
    }

    private String convertTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int colCount = rows.get(0).getTableCells().size();

        // Header row
        XWPFTableRow headerRow = rows.get(0);
        sb.append("|");
        for (XWPFTableCell cell : headerRow.getTableCells()) {
            sb.append(" ").append(cellText(cell)).append(" |");
        }
        sb.append("\n|");
        for (int i = 0; i < colCount; i++) {
            sb.append(" --- |");
        }

        // Data rows
        for (int r = 1; r < rows.size(); r++) {
            sb.append("\n|");
            for (XWPFTableCell cell : rows.get(r).getTableCells()) {
                sb.append(" ").append(cellText(cell)).append(" |");
            }
        }

        return sb.toString();
    }

    private String cellText(XWPFTableCell cell) {
        String text = cell.getText();
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ").trim();
    }
}
