package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time utility to generate golden PDF test samples and their expected snapshots.
 * Run via IDE or {@code java -cp ... GoldenPdfGenerator <output-dir>}.
 * <p>
 * Generates 16 new PDFs (4 categories × 4 new each, complementing the existing 4)
 * and auto-generates matching {@code .segments.json} snapshots by running each PDF
 * through {@link PdfDocumentParser} with the same configuration as
 * {@link GoldenPdfValidationTest}.
 */
public class GoldenPdfGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path outputDir;
    private final PdfDocumentParser parser;

    public GoldenPdfGenerator(Path outputDir) {
        this.outputDir = outputDir;
        this.parser = new PdfDocumentParser(
                new NoopVdpEngine(),
                Runnable::run,
                150, 80, 2, 2, 5000L, 120000L, 144f
        );
    }

    public static void main(String[] args) throws Exception {
        Path base = args.length > 0
                ? Path.of(args[0])
                : Path.of("bootstrap/src/test/resources/golden-pdfs");

        if (!Files.isDirectory(base)) {
            System.err.println("Output directory not found: " + base.toAbsolutePath());
            System.exit(1);
        }

        GoldenPdfGenerator gen = new GoldenPdfGenerator(base);
        gen.generateAll();
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    void generateAll() throws Exception {
        List<GeneratedSample> all = new ArrayList<>();
        all.addAll(generateHeadings());
        all.addAll(generateTables());
        all.addAll(generateScanned());
        all.addAll(generateMixed());

        for (GeneratedSample sample : all) {
            // Write PDF
            Path categoryDir = outputDir.resolve(sample.category);
            Files.createDirectories(categoryDir);
            Path pdfPath = categoryDir.resolve(sample.name + ".pdf");
            Files.write(pdfPath, sample.pdfBytes);
            System.out.println("  PDF: " + pdfPath);

            // Parse and generate snapshot
            ParseResult result = parser.parse(
                    () -> new java.io.ByteArrayInputStream(sample.pdfBytes),
                    "application/pdf",
                    Map.of("fileSizeBytes", sample.pdfBytes.length)
            );

            Map<String, Object> snapshot = buildSnapshot(sample.name, result);
            Path expectedDir = outputDir.resolve("expected");
            Files.createDirectories(expectedDir);
            Path snapshotPath = expectedDir.resolve(sample.name + ".segments.json");
            MAPPER.writeValue(snapshotPath.toFile(), snapshot);
            System.out.println("  JSON: " + snapshotPath);
        }

        System.out.println("\nDone. Generated " + all.size() + " PDF + snapshot pairs.");
    }

    // =========================================================================
    // Headings (纯文本标题结构) — 4 new: heading-02..05
    // =========================================================================

    private List<GeneratedSample> generateHeadings() throws Exception {
        List<GeneratedSample> list = new ArrayList<>();

        // heading-02: 3-level heading hierarchy with body text (150+ chars per section)
        list.add(new GeneratedSample("headings", "heading-02",
                createMultiHeadingPdf(
                        "Employee Handbook",
                        "1. General Policies",
                        "1.1 Attendance",
                        "All employees are expected to maintain regular attendance and notify their manager at least two hours before the start of the shift if they will be late or absent.",
                        "1.2 Dress Code",
                        "The company maintains a business casual dress code. Employees should dress appropriately for their role and any client-facing activities scheduled during the work day."
                )));

        // heading-03: Flat prose text, no headings (abstract/paragraph style)
        list.add(new GeneratedSample("headings", "heading-03",
                createProsePdf(
                        "Abstract: This document provides a comprehensive overview of the quarterly financial performance review process. " +
                        "All departments are required to submit their budget reports by the fifteenth of each month. " +
                        "The finance team will consolidate these reports and present a summary to the executive committee. " +
                        "Any discrepancies identified during the review must be resolved within five business days. " +
                        "The final consolidated report will be published in the company intranet for transparency."
                )));

        // heading-04: 2 pages — page 1 long text (400+ chars), page 2 short text (<80 chars with punctuation)
        list.add(new GeneratedSample("headings", "heading-04",
                createMultiPagePdf(
                        "Chapter 1: Project Overview. The ChatAgent project aims to build an intelligent conversational agent " +
                        "capable of handling multi-turn dialogues with context awareness. The system integrates retrieval-augmented " +
                        "generation with a hierarchical intent routing engine. Key components include the document ingestion pipeline, " +
                        "vector similarity search engine, BGE reranker service, and the agent thinking engine that orchestrates " +
                        "tool calls and response generation. Performance targets include sub-200ms retrieval latency and 95th percentile " +
                        "end-to-end response time under five seconds.",
                        "This is the end."
                )));

        // heading-05: Single page, short narrative (<150 chars, >80 chars, with punctuation)
        list.add(new GeneratedSample("headings", "heading-05",
                createProsePdf(
                        "The quarterly review meeting is scheduled for next Friday at 2pm."
                )));

        return list;
    }

    // =========================================================================
    // Tables (表格密集) — 4 new: table-02..05
    // =========================================================================

    private List<GeneratedSample> generateTables() throws Exception {
        List<GeneratedSample> list = new ArrayList<>();

        // table-02: Simple 3-column × 8-row table drawn with PDFBox
        list.add(new GeneratedSample("tables", "table-02",
                createDrawnTablePdf(new String[]{"Name", "Department", "Role"},
                        new String[][]{
                                {"Alice", "Engineering", "Senior Developer"},
                                {"Bob", "Marketing", "Campaign Manager"},
                                {"Carol", "Finance", "Analyst"},
                                {"Dave", "Engineering", "Tech Lead"},
                                {"Eve", "HR", "Recruiter"},
                                {"Frank", "Sales", "Account Executive"},
                                {"Grace", "Engineering", "QA Engineer"},
                                {"Hank", "Finance", "Controller"}
                        })));

        // table-03: Long table (4 cols × 20 rows) — enough text for NATIVE_TEXT route
        StringBuilder tableText = new StringBuilder();
        tableText.append("ID  Product          Category   Price\n");
        for (int i = 1; i <= 20; i++) {
            tableText.append(String.format("P%03d  Widget Type %s   Group %c    $%.2f\n",
                    i, (i % 5) + 1, (char) ('A' + (i % 4)), 9.99 + i * 1.5));
        }
        list.add(new GeneratedSample("tables", "table-03",
                createProsePdf(tableText.toString())));

        // table-04: Pipe-delimited compact data (≤80 chars, contains | and digits)
        list.add(new GeneratedSample("tables", "table-04",
                createProsePdf("ID|Name|Score 101|Alice|95 102|Bob|87 103|Carol|92")));

        // table-05: CSV-style 2 pages
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            csv.append(String.format("ROW%d,Department %c,Active,%.1f\n", i, (char) ('A' + i % 5), 100.0 - i));
        }
        String csvStr = csv.toString();
        // Split roughly in half for 2 pages
        int mid = csvStr.length() / 2;
        int splitAt = csvStr.indexOf('\n', mid) + 1;
        list.add(new GeneratedSample("tables", "table-05",
                createMultiPagePdf(
                        csvStr.substring(0, splitAt),
                        csvStr.substring(splitAt)
                )));

        return list;
    }

    // =========================================================================
    // Scanned (扫描/图片) — 4 new: scanned-02..05
    // =========================================================================

    private List<GeneratedSample> generateScanned() throws Exception {
        List<GeneratedSample> list = new ArrayList<>();

        // scanned-02: Single image-only page (Graphics2D text rendered as image)
        list.add(new GeneratedSample("scanned", "scanned-02",
                createImageOnlyPdf(renderTextAsImage("Quarterly Financial Report\n\nRevenue: $2.4M\nExpenses: $1.8M\nNet Profit: $600K", 612, 792))));

        // scanned-03: 2 image-only pages
        list.add(new GeneratedSample("scanned", "scanned-03",
                createMultiPageImagePdf(
                        renderTextAsImage("Page 1: Executive Summary\n\nThe company achieved record growth in Q3.", 612, 792),
                        renderTextAsImage("Page 2: Detailed Analysis\n\nRevenue increased by 15% year over year.", 612, 792)
                )));

        // scanned-04: Image page + page with minimal text (<80 chars, no punctuation)
        list.add(new GeneratedSample("scanned", "scanned-04",
                createMixedImageTextPdf(
                        renderTextAsImage("Scanned Document Content\n\nThis text is embedded in an image.", 612, 792),
                        "Product catalog item preview"
                )));

        // scanned-05: Blank page + image page
        list.add(new GeneratedSample("scanned", "scanned-05",
                createBlankAndImagePdf(
                        renderTextAsImage("This is the second page with content.\nKey findings are listed below.", 612, 792)
                )));

        return list;
    }

    // =========================================================================
    // Mixed (混合内容) — 4 new: mixed-02..05
    // =========================================================================

    private List<GeneratedSample> generateMixed() throws Exception {
        List<GeneratedSample> list = new ArrayList<>();

        // mixed-02: Text page (FAST_TRACK) + pipe-delimited data page (VISUAL_TRACK)
        list.add(new GeneratedSample("mixed", "mixed-02",
                createMultiPagePdf(
                        "Staffing Plan 2026\n\nThe engineering department plans to hire twelve new developers across three teams. " +
                        "The frontend team will add four developers, the backend team will add five developers, and the platform team will add three developers. " +
                        "All positions are expected to be filled by the end of Q2.",
                        "Team|Open|Filled Frontend|4|1 Backend|5|2 Platform|3|0"
                )));

        // mixed-03: 3 pages — text → image → table text
        list.add(new GeneratedSample("mixed", "mixed-03",
                createThreePageMixedPdf(
                        "Annual Report Introduction\n\nThe organization has made significant progress in expanding its digital transformation initiatives. " +
                        "Key achievements include the deployment of a new customer portal, migration of legacy systems to cloud infrastructure, " +
                        "and establishment of a data analytics platform for real-time business intelligence.",
                        renderTextAsImage("Chart: Revenue Growth\n\n| 2024 | $1.2M |\n| 2025 | $2.4M |\n| 2026 | $3.6M |", 612, 792),
                        "Department Budget Allocation\n\nEngineering: 40%\nMarketing: 25%\nOperations: 20%\nHR: 15%\n\nTotal budget: $12M for fiscal year 2026."
                )));

        // mixed-04: Short text page (<80 chars, no punctuation → VISUAL_TRACK) + long text page (FAST_TRACK)
        list.add(new GeneratedSample("mixed", "mixed-04",
                createMultiPagePdf(
                        "Quick status update on the migration",
                        "Migration Progress Report\n\nThe database migration from PostgreSQL 14 to PostgreSQL 16 has been completed successfully across all production instances. " +
                        "The team ran comprehensive validation checks including row count verification, checksum comparisons, and application-level integration tests. " +
                        "No data loss or corruption was detected during the migration process. Performance benchmarks show a twelve percent improvement in query latency."
                )));

        // mixed-05: Page with aligned whitespace (triggers ALIGNED_WHITESPACE) + normal text page
        list.add(new GeneratedSample("mixed", "mixed-05",
                createMultiPagePdf(
                        "Name            Age    Department\n" +
                        "Alice           30     Engineering\n" +
                        "Bob             25     Marketing\n" +
                        "Carol           35     Finance",
                        "The employee directory is updated monthly by the HR department. " +
                        "All new hires are automatically added to the system within their first week. " +
                        "Changes to personal information should be submitted through the self-service portal."
                )));

        return list;
    }

    // =========================================================================
    // PDF creation helpers
    // =========================================================================

    private byte[] createProsePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                // Write text with wrapping
                writeWrappedText(cs, text, 72, 720, 468, 12);
                cs.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createMultiHeadingPdf(String title, String h1, String h2a, String body2a,
                                          String h2b, String body2b) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 720;
                // Title
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                cs.newLineAtOffset(72, y);
                cs.showText(title);
                cs.endText();
                y -= 30;
                // H1
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(72, y);
                cs.showText(h1);
                cs.endText();
                y -= 24;
                // H2a
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                cs.newLineAtOffset(90, y);
                cs.showText(h2a);
                cs.endText();
                y -= 18;
                // Body 2a
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(90, y);
                writeWrappedText(cs, body2a, 90, y, 430, 10);
                cs.endText();
                y -= 50;
                // H2b
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                cs.newLineAtOffset(90, y);
                cs.showText(h2b);
                cs.endText();
                y -= 18;
                // Body 2b
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(90, y);
                writeWrappedText(cs, body2b, 90, y, 430, 10);
                cs.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createMultiPagePdf(String... pageTexts) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (pageText != null && !pageText.isEmpty()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 720);
                        writeWrappedText(cs, pageText, 72, 720, 468, 12);
                        cs.endText();
                    }
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createDrawnTablePdf(String[] headers, String[][] rows) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            float margin = 50;
            float yStart = 700;
            float rowHeight = 20;
            float[] colWidths = {150, 150, 180};
            float tableWidth = 480;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = yStart;
                // Header row
                drawTableRow(cs, headers, margin, y, colWidths, true);
                y -= rowHeight;
                // Data rows
                for (String[] row : rows) {
                    drawTableRow(cs, row, margin, y, colWidths, false);
                    y -= rowHeight;
                }
                // Draw border
                cs.setLineWidth(1f);
                cs.addRect(margin, y, tableWidth, yStart - y);
                cs.stroke();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private void drawTableRow(PDPageContentStream cs, String[] cells, float x, float y,
                               float[] colWidths, boolean bold) throws IOException {
        float currentX = x;
        cs.setFont(new PDType1Font(bold ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA), 10);
        for (int i = 0; i < cells.length; i++) {
            cs.beginText();
            cs.newLineAtOffset(currentX + 4, y - 14);
            cs.showText(cells[i]);
            cs.endText();
            // Column separator
            if (i < cells.length - 1) {
                cs.moveTo(currentX + colWidths[i], y);
                cs.lineTo(currentX + colWidths[i], y - 20);
                cs.stroke();
            }
            currentX += colWidths[i];
        }
    }

    // --- Image-based PDF helpers ---

    private BufferedImage renderTextAsImage(String text, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        int y = 40;
        for (String line : text.split("\n")) {
            g.drawString(line, 40, y);
            y += 20;
        }
        g.dispose();
        return img;
    }

    private byte[] createImageOnlyPdf(BufferedImage... images) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            for (BufferedImage img : images) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                        doc, imageToBytes(img), "image");
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createMultiPageImagePdf(BufferedImage... images) throws Exception {
        return createImageOnlyPdf(images);
    }

    private byte[] createMixedImageTextPdf(BufferedImage image, String text) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Page 1: image
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                    doc, imageToBytes(image), "image");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.drawImage(pdImage, 0, 0, page1.getMediaBox().getWidth(), page1.getMediaBox().getHeight());
            }
            // Page 2: text
            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText(text);
                cs.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createBlankAndImagePdf(BufferedImage image) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Page 1: blank
            doc.addPage(new PDPage());
            // Page 2: image
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                    doc, imageToBytes(image), "image");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.drawImage(pdImage, 0, 0, page2.getMediaBox().getWidth(), page2.getMediaBox().getHeight());
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] createThreePageMixedPdf(String text1, BufferedImage image, String text3) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Page 1: text
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                writeWrappedText(cs, text1, 72, 720, 468, 12);
                cs.endText();
            }
            // Page 2: image
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                    doc, imageToBytes(image), "image");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.drawImage(pdImage, 0, 0, page2.getMediaBox().getWidth(), page2.getMediaBox().getHeight());
            }
            // Page 3: text
            PDPage page3 = new PDPage();
            doc.addPage(page3);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page3)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                writeWrappedText(cs, text3, 72, 720, 468, 12);
                cs.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] imageToBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    // =========================================================================
    // Text wrapping helper
    // =========================================================================

    private void writeWrappedText(PDPageContentStream cs, String text, float x, float y,
                                   float maxWidth, float fontSize) throws IOException {
        // Simple: just show the entire text. For golden tests, content correctness matters
        // more than visual layout. Strip any characters that Helvetica can't handle.
        // Strip control chars and other chars Helvetica can't handle
        String safe = text.replaceAll("[\\t\\n\\r\\f\\e]", " ");
        cs.showText(safe);
    }

    // =========================================================================
    // Snapshot builder
    // =========================================================================

    private Map<String, Object> buildSnapshot(String documentId, ParseResult result) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", documentId);
        snapshot.put("expectedSegmentCount", result.getSegments().size());
        snapshot.put("expectedExtractionMode", result.getExtractionMode());

        List<Map<String, Object>> segments = new ArrayList<>();
        for (ParseSegment seg : result.getSegments()) {
            Map<String, Object> segMap = new LinkedHashMap<>();
            segMap.put("pageIndex", seg.index());

            Object route = seg.metadata() != null ? seg.metadata().get("pageRoute") : null;
            if (route != null) {
                segMap.put("expectedRoute", route.toString());
            }

            List<String> mustContain = extractKeyPhrases(seg.text());
            if (!mustContain.isEmpty()) {
                segMap.put("mustContain", mustContain);
            }

            List<String> mustNotContain = new ArrayList<>();
            mustNotContain.add("[图像解析失败]");
            segMap.put("mustNotContain", mustNotContain);

            Object visualType = seg.metadata() != null ? seg.metadata().get("visualType") : null;
            if (visualType != null) {
                segMap.put("expectedVisualType", visualType.toString());
            }

            segments.add(segMap);
        }
        snapshot.put("segments", segments);
        return snapshot;
    }

    private List<String> extractKeyPhrases(String text) {
        if (text == null || text.isBlank()) return List.of();
        // Take up to 3 representative words/phrases from the text
        List<String> phrases = new ArrayList<>();
        String[] words = text.split("\\s+");
        for (int i = 0; i < words.length && phrases.size() < 3; i++) {
            String w = words[i].trim();
            if (w.length() >= 3 && w.matches(".*[a-zA-Z].*") && !w.matches("^[^a-zA-Z]*$")) {
                // Clean up punctuation
                w = w.replaceAll("^[^a-zA-Z]+", "").replaceAll("[^a-zA-Z]+$", "");
                if (w.length() >= 3) {
                    phrases.add(w);
                }
            }
        }
        return phrases;
    }

    // =========================================================================
    // Data carrier
    // =========================================================================

    private record GeneratedSample(String category, String name, byte[] pdfBytes) {
    }
}
