package com.yulong.chatagent.rag.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WordDocumentParserTest {

    private final WordDocumentParser parser = new WordDocumentParser();

    @Test
    void shouldEmitMarkdownContentFormat() {
        byte[] docx = createDocxWithParagraphs("Hello world");
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Map.of());

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentFormat", "MARKDOWN")
                .containsEntry("docType", "word")
                .containsEntry("sourceFormat", "docx")
                .containsEntry("parserType", "word-poi");
    }

    @Test
    void shouldConvertHeadingsToMarkdown() {
        byte[] docx = createDocxWithStyledParagraph("Introduction", "Heading1");
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), null, Map.of());

        assertThat(result.getSegments().get(0).text()).startsWith("# Introduction");
    }

    @Test
    void shouldConvertHeading2() {
        byte[] docx = createDocxWithStyledParagraph("Details", "Heading 2");
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), null, Map.of());

        assertThat(result.getSegments().get(0).text()).startsWith("## Details");
    }

    @Test
    void shouldConvertTitleToH1() {
        byte[] docx = createDocxWithStyledParagraph("Main Title", "Title");
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), null, Map.of());

        assertThat(result.getSegments().get(0).text()).startsWith("# Main Title");
    }

    @Test
    void shouldConvertSubtitleToH2() {
        byte[] docx = createDocxWithStyledParagraph("Sub", "Subtitle");
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), null, Map.of());

        assertThat(result.getSegments().get(0).text()).startsWith("## Sub");
    }

    @Test
    void shouldConvertNormalParagraphs() {
        byte[] docx = createDocxWithParagraphs("First paragraph", "Second paragraph");
        ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), null, Map.of());

        String text = result.getSegments().get(0).text();
        assertThat(text).contains("First paragraph");
        assertThat(text).contains("Second paragraph");
    }

    @Test
    void shouldConvertTableToMarkdownTable() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Name");
            table.getRow(0).getCell(1).setText("Value");
            table.getRow(1).getCell(0).setText("Speed");
            table.getRow(1).getCell(1).setText("Fast");

            byte[] docx = serializeDocx(doc);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(docx), null, Map.of());

            String text = result.getSegments().get(0).text();
            assertThat(text).contains("| Name | Value |");
            assertThat(text).contains("| --- | --- |");
            assertThat(text).contains("| Speed | Fast |");
        }
    }

    @Test
    void shouldSupportsDocxOnly() {
        assertThat(parser.supports(DetectedFileType.accepted("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).isTrue();
        assertThat(parser.supports(DetectedFileType.accepted("pdf", "application/pdf"))).isFalse();
        assertThat(parser.supports(DetectedFileType.accepted("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))).isFalse();
        assertThat(parser.supports(DetectedFileType.accepted("docx", "application/octet-stream"))).isTrue();
    }

    @Test
    void shouldParserTypeBeWord() {
        assertThat(parser.getParserType()).isEqualTo(ParserType.WORD.getType());
    }

    private byte[] createDocxWithParagraphs(String... texts) {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String text : texts) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText(text);
            }
            return serializeDocx(doc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] createDocxWithStyledParagraph(String text, String style) {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.setStyle(style);
            XWPFRun run = p.createRun();
            run.setText(text);
            return serializeDocx(doc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] serializeDocx(XWPFDocument doc) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.write(baos);
        return baos.toByteArray();
    }
}
