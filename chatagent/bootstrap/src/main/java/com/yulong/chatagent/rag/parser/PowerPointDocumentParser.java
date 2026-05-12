package com.yulong.chatagent.rag.parser;

import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Structure-aware parser for PowerPoint .pptx files.
 * Converts each slide to Markdown and emits one PAGE segment per slide,
 * enabling logical Markdown page combining in the chunker router.
 */
@Component
public class PowerPointDocumentParser implements DocumentParser {

    @Override
    public String getParserType() {
        return ParserType.POWERPOINT.getType();
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                return ParseResult.ofText("");
            }
            XMLSlideShow slideshow = new XMLSlideShow(stream);
            List<ParseSegment> segments = new ArrayList<>();
            int slideIndex = 0;

            for (XSLFSlide slide : slideshow.getSlides()) {
                String slideMarkdown = convertSlide(slide, slideIndex + 1);
                if (slideMarkdown.isEmpty()) {
                    slideIndex++;
                    continue;
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("docType", "powerpoint");
                metadata.put("sourceFormat", "pptx");
                metadata.put("parserType", "powerpoint-poi");
                metadata.put("contentFormat", "MARKDOWN");
                metadata.put("pageNumber", slideIndex + 1);
                metadata.put("slideNumber", slideIndex + 1);

                String slideTitle = slide.getTitle();
                if (slideTitle != null && !slideTitle.isBlank()) {
                    metadata.put("slideTitle", slideTitle.trim());
                }

                segments.add(new ParseSegment(slideMarkdown, slideIndex, SegmentType.PAGE, metadata));
                slideIndex++;
            }

            slideshow.close();

            return ParseResult.builder()
                    .segments(segments)
                    .parserType(ParserType.POWERPOINT.getType())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PowerPoint document", e);
        }
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && !type.rejected() && type.isPowerPoint();
    }

    String convertSlide(XSLFSlide slide, int slideNumber) {
        StringBuilder sb = new StringBuilder();
        String title = slide.getTitle();
        String heading = title != null && !title.isBlank()
                ? "# Slide " + slideNumber + ": " + title.trim()
                : "# Slide " + slideNumber;
        sb.append(heading);

        for (var shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                appendTextShape(sb, textShape);
            } else if (shape instanceof XSLFTable table) {
                appendTable(sb, table);
            }
        }

        return sb.toString();
    }

    private void appendTextShape(StringBuilder sb, XSLFTextShape textShape) {
        List<XSLFTextParagraph> paragraphs = textShape.getTextParagraphs();
        if (paragraphs.isEmpty()) {
            return;
        }

        for (XSLFTextParagraph para : paragraphs) {
            String text = para.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            text = text.trim();
            sb.append("\n\n");

            int level = para.getIndentLevel();
            if (level < 0) {
                level = 0;
            }
            if (para.isBullet()) {
                String indent = "  ".repeat(level);
                sb.append(indent).append("- ").append(text);
            } else {
                sb.append(text);
            }
        }
    }

    private void appendTable(StringBuilder sb, XSLFTable table) {
        List<XSLFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }

        int colCount = rows.get(0).getCells().size();
        sb.append("\n\n|");
        for (XSLFTableCell cell : rows.get(0).getCells()) {
            sb.append(" ").append(cellText(cell)).append(" |");
        }
        sb.append("\n|");
        for (int i = 0; i < colCount; i++) {
            sb.append(" --- |");
        }
        for (int r = 1; r < rows.size(); r++) {
            sb.append("\n|");
            for (XSLFTableCell cell : rows.get(r).getCells()) {
                sb.append(" ").append(cellText(cell)).append(" |");
            }
        }
    }

    private String cellText(XSLFTableCell cell) {
        String text = cell.getText();
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ").trim();
    }
}
