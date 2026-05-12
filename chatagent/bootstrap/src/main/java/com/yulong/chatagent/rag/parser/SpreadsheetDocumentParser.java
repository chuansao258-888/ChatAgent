package com.yulong.chatagent.rag.parser;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Structure-aware parser for spreadsheet files (.xlsx).
 * Extracts sheet/table regions and emits TABLE segments with row/column metadata.
 */
@Component
public class SpreadsheetDocumentParser implements DocumentParser {

    private static final int MAX_SHEETS = 20;
    private static final int MAX_ROWS_PER_SHEET = 10_000;
    private static final int MAX_COLUMNS = 200;

    private final DataFormatter dataFormatter = new DataFormatter();

    @Override
    public String getParserType() {
        return ParserType.SPREADSHEET.getType();
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                return ParseResult.ofText("");
            }
            Workbook workbook = WorkbookFactory.create(stream);
            List<ParseSegment> segments = extractSegments(workbook);
            workbook.close();

            return ParseResult.builder()
                    .segments(segments)
                    .parserType(ParserType.SPREADSHEET.getType())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse spreadsheet", e);
        }
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && !type.rejected() && type.isSpreadsheet();
    }

    List<ParseSegment> extractSegments(Workbook workbook) {
        List<ParseSegment> segments = new ArrayList<>();
        int sheetCount = Math.min(workbook.getNumberOfSheets(), MAX_SHEETS);
        int globalTableIndex = 0;
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        for (int s = 0; s < sheetCount; s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (sheet.getPhysicalNumberOfRows() == 0) {
                continue;
            }

            List<TableRegion> regions = detectTableRegions(sheet, evaluator);
            for (TableRegion region : regions) {
                String content = buildMarkdownTable(sheet, region, evaluator);
                if (content.isEmpty()) {
                    continue;
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("docType", "spreadsheet");
                metadata.put("sourceFormat", "xlsx");
                metadata.put("parserType", "spreadsheet-poi");
                metadata.put("contentFormat", "TABLE");
                metadata.put("sheetName", sheet.getSheetName());
                metadata.put("sheetIndex", s);
                metadata.put("tableIndex", globalTableIndex);
                metadata.put("rowStart", region.rowStart + 1);
                metadata.put("rowEnd", region.rowEnd);
                metadata.put("columnStart", region.colStart + 1);
                metadata.put("columnEnd", region.colEnd);
                metadata.put("headerRowStart", region.rowStart + 1);
                metadata.put("headerRowEnd", region.rowStart + 1);

                String range = cellRef(region.colStart, region.rowStart) + ":" + cellRef(region.colEnd - 1, region.rowEnd - 1);
                metadata.put("range", range);

                boolean hasFormula = hasFormulaCells(sheet, region);
                if (hasFormula) {
                    metadata.put("hasFormula", true);
                }

                segments.add(new ParseSegment(content, globalTableIndex, SegmentType.TABLE, metadata));
                globalTableIndex++;
            }
        }
        return segments;
    }

    List<TableRegion> detectTableRegions(Sheet sheet, FormulaEvaluator evaluator) {
        int firstRow = sheet.getFirstRowNum();
        int lastRow = Math.min(sheet.getLastRowNum(), firstRow + MAX_ROWS_PER_SHEET - 1);

        int maxCol = 0;
        for (int r = firstRow; r <= lastRow; r++) {
            var row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > maxCol) {
                maxCol = row.getLastCellNum();
            }
        }
        maxCol = Math.min(maxCol, MAX_COLUMNS);

        List<TableRegion> regions = new ArrayList<>();
        int regionStart = -1;
        for (int r = firstRow; r <= lastRow; r++) {
            var row = sheet.getRow(r);
            boolean isEmptyRow = row == null || isRowEmpty(row, maxCol, evaluator);
            if (!isEmptyRow) {
                if (regionStart < 0) {
                    regionStart = r;
                }
            } else {
                if (regionStart >= 0) {
                    regions.add(new TableRegion(regionStart, r, 0, maxCol));
                    regionStart = -1;
                }
            }
        }
        if (regionStart >= 0) {
            regions.add(new TableRegion(regionStart, lastRow + 1, 0, maxCol));
        }
        return regions;
    }

    private boolean isRowEmpty(org.apache.poi.ss.usermodel.Row row, int maxCol, FormulaEvaluator evaluator) {
        for (int c = 0; c < maxCol; c++) {
            var cell = row.getCell(c);
            if (cell != null) {
                String val = dataFormatter.formatCellValue(cell, evaluator);
                if (val != null && !val.isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    private String buildMarkdownTable(Sheet sheet, TableRegion region, FormulaEvaluator evaluator) {
        if (region.rowEnd <= region.rowStart) {
            return "";
        }
        int colStart = region.colStart;
        int colEnd = region.colEnd;

        StringBuilder sb = new StringBuilder();
        for (int r = region.rowStart; r < region.rowEnd; r++) {
            var row = sheet.getRow(r);
            if (r > region.rowStart) {
                sb.append("\n");
            }
            sb.append("|");
            for (int c = colStart; c < colEnd; c++) {
                String cellVal = "";
                if (row != null) {
                    var cell = row.getCell(c);
                    if (cell != null) {
                        cellVal = dataFormatter.formatCellValue(cell, evaluator);
                    }
                }
                sb.append(" ").append(escapeCell(cellVal)).append(" |");
            }
            if (r == region.rowStart) {
                sb.append("\n|");
                for (int c = colStart; c < colEnd; c++) {
                    sb.append(" --- |");
                }
            }
        }
        return sb.toString();
    }

    private boolean hasFormulaCells(Sheet sheet, TableRegion region) {
        for (int r = region.rowStart; r < region.rowEnd; r++) {
            var row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = region.colStart; c < region.colEnd; c++) {
                var cell = row.getCell(c);
                if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                    return true;
                }
            }
        }
        return false;
    }

    private String escapeCell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ").trim();
    }

    private String cellRef(int col, int row) {
        StringBuilder sb = new StringBuilder();
        int c = col;
        while (c >= 0) {
            sb.insert(0, (char) ('A' + (c % 26)));
            c = c / 26 - 1;
        }
        sb.append(row + 1);
        return sb.toString();
    }

    record TableRegion(int rowStart, int rowEnd, int colStart, int colEnd) {
    }
}
