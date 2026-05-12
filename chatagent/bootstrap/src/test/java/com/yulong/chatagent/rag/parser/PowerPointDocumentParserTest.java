package com.yulong.chatagent.rag.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PowerPointDocumentParserTest {

    private final PowerPointDocumentParser parser = new PowerPointDocumentParser();

    @Test
    void shouldEmitOnePageSegmentPerSlide() {
        byte[] pptx = createPptxWithSlideCount(3);
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

        assertThat(result.getSegments()).hasSize(3);
        assertThat(result.getParserType()).isEqualTo(ParserType.POWERPOINT.getType());
    }

    @Test
    void shouldMarkSegmentsAsMarkdown() {
        byte[] pptx = createPptxWithSlideCount(1);
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentFormat", "MARKDOWN")
                .containsEntry("docType", "powerpoint")
                .containsEntry("sourceFormat", "pptx")
                .containsEntry("parserType", "powerpoint-poi");
    }

    @Test
    void shouldIncludeSlideNumberInMetadata() {
        byte[] pptx = createPptxWithSlideCount(2);
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageNumber", 1)
                .containsEntry("slideNumber", 1);
        assertThat(result.getSegments().get(1).metadata())
                .containsEntry("pageNumber", 2)
                .containsEntry("slideNumber", 2);
    }

    @Test
    void shouldConvertSlideTitleToHeading() throws Exception {
        try (XMLSlideShow slideshow = new XMLSlideShow()) {
            XSLFSlide slide = slideshow.createSlide();
            XSLFTextShape titleShape = slide.createTextBox();
            titleShape.setText("Architecture Overview");

            byte[] pptx = serializePptx(slideshow);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

            String text = result.getSegments().get(0).text();
            assertThat(text).startsWith("# Slide 1");
            assertThat(text).contains("Architecture Overview");
        }
    }

    @Test
    void shouldConvertBulletsToMarkdownList() throws Exception {
        try (XMLSlideShow slideshow = new XMLSlideShow()) {
            XSLFSlide slide = slideshow.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            XSLFTextParagraph p1 = textBox.addNewTextParagraph();
            p1.setBullet(true);
            XSLFTextRun r1 = p1.addNewTextRun();
            r1.setText("First bullet");
            XSLFTextParagraph p2 = textBox.addNewTextParagraph();
            p2.setBullet(true);
            XSLFTextRun r2 = p2.addNewTextRun();
            r2.setText("Second bullet");

            byte[] pptx = serializePptx(slideshow);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

            String text = result.getSegments().get(0).text();
            assertThat(text).contains("- First bullet");
            assertThat(text).contains("- Second bullet");
        }
    }

    @Test
    void shouldConvertTableToMarkdownTable() throws Exception {
        try (XMLSlideShow slideshow = new XMLSlideShow()) {
            XSLFSlide slide = slideshow.createSlide();
            XSLFTable table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("Metric");
            table.getCell(0, 1).setText("Value");
            table.getCell(1, 0).setText("Latency");
            table.getCell(1, 1).setText("120ms");

            byte[] pptx = serializePptx(slideshow);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

            String text = result.getSegments().get(0).text();
            assertThat(text).contains("| Metric | Value |");
            assertThat(text).contains("| Latency | 120ms |");
        }
    }

    @Test
    void shouldOmitSlideTitleWhenNoTitlePlaceholder() {
        byte[] pptx = createPptxWithSlideCount(1);
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(pptx), null, Map.of());

        // slideTitle is only populated when slide.getTitle() returns a non-empty value
        assertThat(result.getSegments().get(0).metadata())
                .doesNotContainKey("slideTitle");
    }

    @Test
    void shouldSupportsPptxOnly() {
        assertThat(parser.supports(DetectedFileType.accepted("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))).isTrue();
        assertThat(parser.supports(DetectedFileType.accepted("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).isFalse();
        assertThat(parser.supports(DetectedFileType.accepted("pdf", "application/pdf"))).isFalse();
    }

    private byte[] createPptxWithSlideCount(int count) {
        try (XMLSlideShow slideshow = new XMLSlideShow()) {
            for (int i = 0; i < count; i++) {
                XSLFSlide slide = slideshow.createSlide();
                XSLFTextBox textBox = slide.createTextBox();
                XSLFTextParagraph para = textBox.addNewTextParagraph();
                XSLFTextRun run = para.addNewTextRun();
                run.setText("Slide " + (i + 1) + " content");
            }
            return serializePptx(slideshow);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] serializePptx(XMLSlideShow slideshow) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        slideshow.write(baos);
        return baos.toByteArray();
    }
}
