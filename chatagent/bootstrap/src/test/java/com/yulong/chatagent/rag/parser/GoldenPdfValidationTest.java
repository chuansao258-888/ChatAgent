package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Golden-sample validation test for the PDF parsing pipeline.
 *
 * <p>Discovers all PDFs under {@code golden-pdfs/} in test resources, parses each
 * through {@link PdfDocumentParser}, and compares the output against the matching
 * {@code expected/*.segments.json} snapshot.
 *
 * <p>This test is <b>excluded from normal CI runs</b>. Execute manually:
 * <pre>
 *   mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest
 * </pre>
 */
@Tag("golden")
class GoldenPdfValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOLDEN_ROOT = "golden-pdfs";
    private static final String[] CATEGORY_DIRS = {"scanned", "tables", "headings", "mixed"};

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("goldenPdfCases")
    void shouldMatchExpectedSnapshot(GoldenPdfCase testCase) throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser(
                new NoopVdpEngine(),
                Runnable::run,
                150, 80, 2, 2, 5000L, 120000L, 144f
        );

        byte[] pdfBytes = Files.readAllBytes(testCase.pdfPath());

        ParseResult result = parser.parse(
                () -> new ByteArrayInputStream(pdfBytes),
                "application/pdf",
                Map.of("fileSizeBytes", pdfBytes.length)
        );

        ExpectedSnapshot expected = testCase.expected();

        assertSoftly(softly -> {
            // --- document-level assertions ---
            if (expected.expectedSegmentCount() != null) {
                softly.assertThat(result.getSegments())
                        .as("segment count for %s", expected.documentId())
                        .hasSize(expected.expectedSegmentCount());
            }

            if (expected.expectedExtractionMode() != null) {
                softly.assertThat(result.getExtractionMode())
                        .as("extraction mode for %s", expected.documentId())
                        .isEqualTo(expected.expectedExtractionMode());
            }

            softly.assertThat(result.getParserType())
                    .as("parser type")
                    .isEqualTo(ParserType.PDFBOX.getType());

            // --- per-page segment assertions ---
            if (expected.segments() != null) {
                for (ExpectedSegment seg : expected.segments()) {
                    ParseSegment actual = findSegmentByPageIndex(result.getSegments(), seg.pageIndex());
                    if (actual == null) {
                        softly.fail("No segment found for pageIndex=%d in %s", seg.pageIndex(), expected.documentId());
                        continue;
                    }

                    if (seg.expectedRoute() != null) {
                        Object pageRoute = actual.metadata().get("pageRoute");
                        softly.assertThat(pageRoute)
                                .as("page %d route in %s", seg.pageIndex(), expected.documentId())
                                .isEqualTo(seg.expectedRoute());
                    }

                    if (seg.mustContain() != null) {
                        for (String needle : seg.mustContain()) {
                            softly.assertThat(actual.text())
                                    .as("page %d must contain '%s' in %s", seg.pageIndex(), needle, expected.documentId())
                                    .containsIgnoringCase(needle);
                        }
                    }

                    if (seg.mustNotContain() != null) {
                        for (String needle : seg.mustNotContain()) {
                            softly.assertThat(actual.text())
                                    .as("page %d must not contain '%s' in %s", seg.pageIndex(), needle, expected.documentId())
                                    .doesNotContainIgnoringCase(needle);
                        }
                    }

                    if (seg.expectedVisualType() != null) {
                        Object visualType = actual.metadata().get("visualType");
                        softly.assertThat(visualType)
                                .as("page %d visual type in %s", seg.pageIndex(), expected.documentId())
                                .isEqualTo(seg.expectedVisualType());
                    }
                }
            }
        });
    }

    // ---- test case discovery ----

    static Stream<GoldenPdfCase> goldenPdfCases() throws IOException {
        Path goldenRoot = resolveGoldenRoot();
        Path expectedDir = goldenRoot.resolve("expected");
        List<GoldenPdfCase> cases = new ArrayList<>();

        for (String category : CATEGORY_DIRS) {
            Path categoryDir = goldenRoot.resolve(category);
            if (!Files.isDirectory(categoryDir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(categoryDir, "*.pdf")) {
                for (Path pdf : stream) {
                    String baseName = stripExtension(pdf.getFileName().toString());
                    Path expectedFile = expectedDir.resolve(baseName + ".segments.json");
                    if (!Files.exists(expectedFile)) {
                        throw new IllegalStateException(
                                "Missing expected snapshot for " + pdf.getFileName()
                                        + " — create " + expectedFile.toAbsolutePath()
                        );
                    }
                    ExpectedSnapshot snapshot = MAPPER.readValue(expectedFile.toFile(), ExpectedSnapshot.class);
                    cases.add(new GoldenPdfCase(baseName, pdf, snapshot));
                }
            }
        }

        if (cases.isEmpty()) {
            throw new IllegalStateException(
                    "No golden PDF samples found under " + goldenRoot.toAbsolutePath()
                            + ". Add PDF files to scanned/, tables/, headings/, or mixed/ "
                            + "and create matching expected/*.segments.json snapshots."
            );
        }

        return cases.stream();
    }

    private static Path resolveGoldenRoot() {
        // Try classpath resource first (Maven standard layout)
        java.net.URL resource = GoldenPdfValidationTest.class.getClassLoader().getResource(GOLDEN_ROOT);
        if (resource != null) {
            try {
                return Path.of(resource.toURI());
            } catch (Exception ignored) {
                // fall through
            }
        }
        // Fallback to direct file path
        Path direct = Path.of("src/test/resources", GOLDEN_ROOT);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        throw new IllegalStateException("Cannot locate golden-pdfs/ directory on classpath or filesystem");
    }

    private static ParseSegment findSegmentByPageIndex(List<ParseSegment> segments, int pageIndex) {
        if (segments == null) {
            return null;
        }
        return segments.stream()
                .filter(s -> s.index() == pageIndex)
                .findFirst()
                .orElse(null);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    // ---- data carriers ----

    record GoldenPdfCase(String name, Path pdfPath, ExpectedSnapshot expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpectedSnapshot(
            String documentId,
            Integer expectedSegmentCount,
            String expectedExtractionMode,
            List<ExpectedSegment> segments
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExpectedSegment(
            int pageIndex,
            String expectedRoute,
            List<String> mustContain,
            List<String> mustNotContain,
            String expectedVisualType
    ) {
    }
}
