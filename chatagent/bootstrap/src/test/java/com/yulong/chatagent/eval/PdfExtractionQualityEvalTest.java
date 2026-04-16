package com.yulong.chatagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.PdfDocumentParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF extraction quality evaluation against 20 golden-sample PDFs.
 *
 * <p>Parses each golden PDF through PdfDocumentParser (with NoopVdpEngine),
 * compares output against expected snapshots, and produces aggregate metrics.
 *
 * <p>Run: mvn test -pl bootstrap -Dsurefire.excludedGroups= -Dgroups=eval-pdf-quality -Dtest=PdfExtractionQualityEvalTest
 */
@Tag("eval-pdf-quality")
class PdfExtractionQualityEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOLDEN_ROOT = "golden-pdfs";
    private static final String[] CATEGORIES = {"headings", "tables", "scanned", "mixed"};

    @Test
    void evaluateExtractionQuality() throws Exception {
        PdfDocumentParser parser = createParser();

        Path goldenRoot = resolveGoldenRoot();
        Path expectedDir = goldenRoot.resolve("expected");

        List<Map<String, Object>> perDocResults = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();

        int totalDocs = 0;
        int segmentCountMatch = 0;
        int extractionModeMatch = 0;
        int totalMustContain = 0;
        int mustContainHits = 0;
        int totalMustNotContain = 0;
        int mustNotContainCorrect = 0;
        int totalRoutes = 0;
        int routeMatch = 0;

        for (String category : CATEGORIES) {
            Path categoryDir = goldenRoot.resolve(category);
            if (!Files.isDirectory(categoryDir)) continue;

            List<Map<String, Object>> categoryResults = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(categoryDir, "*.pdf")) {
                for (Path pdf : stream) {
                    String baseName = stripExtension(pdf.getFileName().toString());
                    Path expectedFile = expectedDir.resolve(baseName + ".segments.json");
                    if (!Files.exists(expectedFile)) continue;

                    ExpectedSnapshot expected = MAPPER.readValue(expectedFile.toFile(), ExpectedSnapshot.class);
                    byte[] pdfBytes = Files.readAllBytes(pdf);

                    ParseResult result = parser.parse(
                            () -> new ByteArrayInputStream(pdfBytes),
                            "application/pdf",
                            Map.of("fileSizeBytes", pdfBytes.length)
                    );

                    totalDocs++;
                    Map<String, Object> docResult = new LinkedHashMap<>();
                    docResult.put("documentId", baseName);
                    docResult.put("category", category);

                    boolean segCountOk = expected.expectedSegmentCount() == null
                            || result.getSegments().size() == expected.expectedSegmentCount();
                    if (segCountOk) segmentCountMatch++;
                    docResult.put("segmentCountMatch", segCountOk);
                    docResult.put("actualSegmentCount", result.getSegments().size());
                    docResult.put("expectedSegmentCount", expected.expectedSegmentCount());

                    boolean modeOk = expected.expectedExtractionMode() == null
                            || expected.expectedExtractionMode().equals(result.getExtractionMode());
                    if (modeOk) extractionModeMatch++;
                    docResult.put("extractionModeMatch", modeOk);
                    docResult.put("actualExtractionMode", result.getExtractionMode());

                    int docMustContain = 0, docMustContainHit = 0;
                    int docMustNotContain = 0, docMustNotContainOk = 0;
                    int docRoutes = 0, docRouteMatch = 0;

                    if (expected.segments() != null) {
                        for (ExpectedSegment seg : expected.segments()) {
                            ParseSegment actual = findByPage(result.getSegments(), seg.pageIndex());

                            if (seg.expectedRoute() != null && actual != null) {
                                docRoutes++;
                                Object pageRoute = actual.metadata().get("pageRoute");
                                if (seg.expectedRoute().equals(pageRoute)) docRouteMatch++;
                            }

                            if (seg.mustContain() != null && actual != null) {
                                for (String phrase : seg.mustContain()) {
                                    docMustContain++;
                                    if (actual.text().toLowerCase().contains(phrase.toLowerCase())) {
                                        docMustContainHit++;
                                    }
                                }
                            }

                            if (seg.mustNotContain() != null && actual != null) {
                                for (String phrase : seg.mustNotContain()) {
                                    docMustNotContain++;
                                    if (!actual.text().toLowerCase().contains(phrase.toLowerCase())) {
                                        docMustNotContainOk++;
                                    }
                                }
                            }
                        }
                    }

                    totalMustContain += docMustContain;
                    mustContainHits += docMustContainHit;
                    totalMustNotContain += docMustNotContain;
                    mustNotContainCorrect += docMustNotContainOk;
                    totalRoutes += docRoutes;
                    routeMatch += docRouteMatch;

                    docResult.put("mustContainRecall", docMustContain == 0 ? 1.0
                            : round(docMustContainHit / (double) docMustContain));
                    docResult.put("mustNotContainPrecision", docMustNotContain == 0 ? 1.0
                            : round(docMustNotContainOk / (double) docMustNotContain));
                    docResult.put("routeAccuracy", docRoutes == 0 ? 1.0
                            : round(docRouteMatch / (double) docRoutes));

                    perDocResults.add(docResult);
                    categoryResults.add(docResult);
                }
            }
            byCategory.put(category, categoryResults);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalDocuments", totalDocs);
        report.put("segmentCountAccuracy", pct(segmentCountMatch, totalDocs));
        report.put("extractionModeAccuracy", pct(extractionModeMatch, totalDocs));
        report.put("contentRecallRate", pct(mustContainHits, totalMustContain));
        report.put("contentPrecisionRate", pct(mustNotContainCorrect, totalMustNotContain));
        report.put("routeAccuracy", pct(routeMatch, totalRoutes));

        Map<String, Object> perCategory = new LinkedHashMap<>();
        for (var entry : byCategory.entrySet()) {
            List<Map<String, Object>> docs = entry.getValue();
            int catDocs = docs.size();
            int catSegMatch = (int) docs.stream().filter(d -> (boolean) d.get("segmentCountMatch")).count();
            int catModeMatch = (int) docs.stream().filter(d -> (boolean) d.get("extractionModeMatch")).count();
            double catRecall = docs.stream().mapToDouble(d -> (double) d.get("mustContainRecall")).average().orElse(0);
            double catPrecision = docs.stream().mapToDouble(d -> (double) d.get("mustNotContainPrecision")).average().orElse(0);
            double catRoute = docs.stream().mapToDouble(d -> (double) d.get("routeAccuracy")).average().orElse(0);

            Map<String, Object> catReport = new LinkedHashMap<>();
            catReport.put("documents", catDocs);
            catReport.put("segmentCountAccuracy", pct(catSegMatch, catDocs));
            catReport.put("extractionModeAccuracy", pct(catModeMatch, catDocs));
            catReport.put("avgContentRecall", round(catRecall));
            catReport.put("avgContentPrecision", round(catPrecision));
            catReport.put("avgRouteAccuracy", round(catRoute));
            perCategory.put(entry.getKey(), catReport);
        }
        report.put("perCategory", perCategory);
        report.put("perDocument", perDocResults);

        Path reportPath = EvalReportWriter.writeReport("pdf-extraction-quality-eval", report);

        System.out.println("\n=== PDF Extraction Quality Evaluation ===");
        System.out.printf("Documents:              %d%n", totalDocs);
        System.out.printf("Segment count accuracy: %.2f%%%n", pct(segmentCountMatch, totalDocs));
        System.out.printf("Extraction mode accuracy: %.2f%%%n", pct(extractionModeMatch, totalDocs));
        System.out.printf("Content recall rate:    %.2f%% (%d/%d phrases)%n",
                pct(mustContainHits, totalMustContain), mustContainHits, totalMustContain);
        System.out.printf("Content precision rate: %.2f%% (%d/%d exclusions)%n",
                pct(mustNotContainCorrect, totalMustNotContain), mustNotContainCorrect, totalMustNotContain);
        System.out.printf("Route accuracy:         %.2f%% (%d/%d pages)%n",
                pct(routeMatch, totalRoutes), routeMatch, totalRoutes);
        System.out.println("\nPer-category:");
        for (var entry : byCategory.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cat = (Map<String, Object>) perCategory.get(entry.getKey());
            System.out.printf("  %-10s — segCount=%.0f%%, mode=%.0f%%, recall=%.2f, precision=%.2f, route=%.2f%n",
                    entry.getKey(),
                    cat.get("segmentCountAccuracy"), cat.get("extractionModeAccuracy"),
                    cat.get("avgContentRecall"), cat.get("avgContentPrecision"), cat.get("avgRouteAccuracy"));
        }
        System.out.println("\nReport: " + reportPath);

        assertThat(totalDocs).as("should discover golden PDFs").isGreaterThanOrEqualTo(15);
        assertThat(pct(segmentCountMatch, totalDocs)).as("segment count accuracy").isGreaterThanOrEqualTo(50.0);
    }

    private static PdfDocumentParser createParser() throws Exception {
        Constructor<PdfDocumentParser> ctor = PdfDocumentParser.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static ParseSegment findByPage(List<ParseSegment> segments, int pageIndex) {
        if (segments == null) return null;
        return segments.stream().filter(s -> s.index() == pageIndex).findFirst().orElse(null);
    }

    private static Path resolveGoldenRoot() {
        java.net.URL resource = PdfExtractionQualityEvalTest.class.getClassLoader().getResource(GOLDEN_ROOT);
        if (resource != null) {
            try { return Path.of(resource.toURI()); } catch (Exception ignored) {}
        }
        Path direct = Path.of("src/test/resources", GOLDEN_ROOT);
        if (Files.isDirectory(direct)) return direct;
        throw new IllegalStateException("Cannot locate golden-pdfs/ directory");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static double pct(int numerator, int denominator) {
        return denominator == 0 ? 100.0 : round(numerator * 100.0 / denominator);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpectedSnapshot(
            String documentId,
            Integer expectedSegmentCount,
            String expectedExtractionMode,
            List<ExpectedSegment> segments
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpectedSegment(
            int pageIndex,
            String expectedRoute,
            List<String> mustContain,
            List<String> mustNotContain,
            String expectedVisualType
    ) {}
}
