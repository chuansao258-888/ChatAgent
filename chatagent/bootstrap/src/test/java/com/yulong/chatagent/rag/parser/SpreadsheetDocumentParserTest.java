package com.yulong.chatagent.rag.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadsheetDocumentParserTest {

    private final SpreadsheetDocumentParser parser = new SpreadsheetDocumentParser();

    @Test
    void shouldEmitTableSegmentsFromSingleSheet() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sales");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Region");
            header.createCell(1).setCellValue("Revenue");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("APAC");
            data.createCell(1).setCellValue(12000);

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            assertThat(result.getSegments()).hasSize(1);
            assertThat(result.getParserType()).isEqualTo(ParserType.SPREADSHEET.getType());
        }
    }

    @Test
    void shouldIncludeSheetMetadata() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Metrics");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Key");
            row.createCell(1).setCellValue("Val");

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            assertThat(result.getSegments().get(0).metadata())
                    .containsEntry("docType", "spreadsheet")
                    .containsEntry("sourceFormat", "xlsx")
                    .containsEntry("parserType", "spreadsheet-poi")
                    .containsEntry("contentFormat", "TABLE")
                    .containsEntry("sheetName", "Metrics")
                    .containsEntry("sheetIndex", 0);
        }
    }

    @Test
    void shouldIncludeRowRangeMetadata() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("A");
            sheet.createRow(1).createCell(0).setCellValue("B");
            sheet.createRow(2).createCell(0).setCellValue("C");

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            Map<String, Object> meta = result.getSegments().get(0).metadata();
            assertThat(meta).containsEntry("rowStart", 1);
            assertThat(meta).containsEntry("rowEnd", 3);
            assertThat(meta).containsKey("range");
        }
    }

    @Test
    void shouldSplitByBlankRows() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Multi");
            sheet.createRow(0).createCell(0).setCellValue("Table1");
            sheet.createRow(1).createCell(0).setCellValue("Data1");
            // Row 2 is blank
            sheet.createRow(3).createCell(0).setCellValue("Table2");
            sheet.createRow(4).createCell(0).setCellValue("Data2");

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            assertThat(result.getSegments()).hasSize(2);
        }
    }

    @Test
    void shouldConvertToMarkdownTable() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Test");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Score");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Alice");
            data.createCell(1).setCellValue(95);

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            String text = result.getSegments().get(0).text();
            assertThat(text).contains("| Name | Score |");
            assertThat(text).contains("| --- | --- |");
            assertThat(text).contains("| Alice | 95 |");
        }
    }

    @Test
    void shouldDetectFormulas() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Calc");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("Total");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(10);
            r1.createCell(1).setCellFormula("A2*2");

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            assertThat(result.getSegments().get(0).metadata())
                    .containsEntry("hasFormula", true);
        }
    }

    @Test
    void shouldSupportsXlsxOnly() {
        assertThat(parser.supports(DetectedFileType.accepted("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))).isTrue();
        assertThat(parser.supports(DetectedFileType.accepted("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).isFalse();
        assertThat(parser.supports(DetectedFileType.accepted("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))).isFalse();
    }

    @Test
    void shouldHandleMultipleSheets() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s1 = wb.createSheet("First");
            s1.createRow(0).createCell(0).setCellValue("A");
            Sheet s2 = wb.createSheet("Second");
            s2.createRow(0).createCell(0).setCellValue("B");

            byte[] xlsx = serializeWorkbook(wb);
            ParseResult result = parser.parse(() -> new ByteArrayInputStream(xlsx), null, Map.of());

            assertThat(result.getSegments()).hasSize(2);
            assertThat(result.getSegments().get(0).metadata()).containsEntry("sheetName", "First");
            assertThat(result.getSegments().get(1).metadata()).containsEntry("sheetName", "Second");
        }
    }

    private byte[] serializeWorkbook(Workbook wb) throws Exception {
        var baos = new java.io.ByteArrayOutputStream();
        wb.write(baos);
        return baos.toByteArray();
    }
}
