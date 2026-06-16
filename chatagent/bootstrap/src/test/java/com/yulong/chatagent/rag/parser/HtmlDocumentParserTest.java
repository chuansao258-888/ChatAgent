package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.rag.ingestion.StructureAwareMarkdownChunker;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlDocumentParserTest {

    private HtmlDocumentParser parser;
    private TikaDocumentParser tikaFallback;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tikaFallback = new TikaDocumentParser();
        parser = new HtmlDocumentParser(tikaFallback);
    }

    private Supplier<InputStream> supplierOf(String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }

    private Supplier<InputStream> emptySupplier() {
        return () -> new ByteArrayInputStream(new byte[0]);
    }

    private ParseResult parse(String html) {
        return parser.parse(supplierOf(html), "text/html", Map.of());
    }

    // -----------------------------------------------------------------------
    // Parser identity and supports()
    // -----------------------------------------------------------------------

    @Test
    void parserTypeIsHtml() {
        assertThat(parser.getParserType()).isEqualTo("Html");
    }

    @SuppressWarnings("removal")
    @Test
    void deprecatedByteArrayParseDelegatesToStreamPath() {
        byte[] html = "<html><body><h1>Legacy entry</h1></body></html>".getBytes(StandardCharsets.UTF_8);

        ParseResult result = parser.parse(html, "text/html", Map.of());

        assertThat(result.getParserType()).isEqualTo("Html");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("# Legacy entry");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentFormat", "MARKDOWN");
    }

    @Test
    void supportsHtmlExtension() {
        assertThat(parser.supports(DetectedFileType.accepted("html", "text/html"))).isTrue();
        assertThat(parser.supports(DetectedFileType.accepted("htm", "text/html"))).isTrue();
        assertThat(parser.supports(DetectedFileType.accepted("html", "application/octet-stream"))).isTrue();
    }

    @Test
    void supportsMimeOnly() {
        assertThat(parser.supports(DetectedFileType.accepted(null, "text/html"))).isTrue();
    }

    @Test
    void doesNotSupportNonHtml() {
        assertThat(parser.supports(DetectedFileType.accepted("pdf", "application/pdf"))).isFalse();
        assertThat(parser.supports(DetectedFileType.accepted("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).isFalse();
        assertThat(parser.supports(DetectedFileType.accepted("txt", "text/plain"))).isFalse();
    }

    @Test
    void doesNotSupportRejected() {
        assertThat(parser.supports(DetectedFileType.rejected("html", "text/html", "forbidden"))).isFalse();
    }

    // -----------------------------------------------------------------------
    // Heading preservation
    // -----------------------------------------------------------------------

    @Test
    void preservesH1ThroughH6() {
        String html = """
                <html><body>
                <h1>Title</h1>
                <h2>Section</h2>
                <h3>Subsection</h3>
                <h4>Deep</h4>
                <h5>Deeper</h5>
                <h6>Deepest</h6>
                </body></html>
                """;
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("# Title");
        assertThat(text).contains("## Section");
        assertThat(text).contains("### Subsection");
        assertThat(text).contains("#### Deep");
        assertThat(text).contains("##### Deeper");
        assertThat(text).contains("###### Deepest");
    }

    // -----------------------------------------------------------------------
    // Table rendering
    // -----------------------------------------------------------------------

    @Nested
    class TableRendering {

        @Test
        void rendersBasicTable() {
            String html = """
                    <table>
                      <tr><th>Name</th><th>Age</th></tr>
                      <tr><td>Alice</td><td>30</td></tr>
                      <tr><td>Bob</td><td>25</td></tr>
                    </table>
                    """;
            ParseResult result = parse(html);
            String text = result.getSegments().get(0).text();

            assertThat(text).contains("| Name | Age |");
            assertThat(text).contains("| --- | --- |");
            assertThat(text).contains("| Alice | 30 |");
            assertThat(text).contains("| Bob | 25 |");
        }

        @Test
        void rendersHeaderlessTableWithSynthesizedHeaders() {
            String html = """
                    <table>
                      <tr><td>Revenue</td><td>1000000</td></tr>
                      <tr><td>Expenses</td><td>800000</td></tr>
                    </table>
                    """;
            ParseResult result = parse(html);
            String text = result.getSegments().get(0).text();

            assertThat(text).contains("| Column 1 | Column 2 |");
            assertThat(text).contains("| --- | --- |");
            assertThat(text).contains("| Revenue | 1000000 |");
        }

        @Test
        void handlesColspan() {
            String html = """
                    <table>
                      <tr><th>Name</th><th>Details</th></tr>
                      <tr><td colspan="2">Spanning both columns</td></tr>
                    </table>
                    """;
            ParseResult result = parse(html);
            String text = result.getSegments().get(0).text();

            assertThat(text).contains("| Name | Details |");
            assertThat(text).contains("| Spanning both columns |  |");
        }

        @Test
        void handlesRowspan() {
            String html = """
                    <table>
                      <tr><th>Category</th><th>Item</th><th>Value</th></tr>
                      <tr><td rowspan="2">Assets</td><td>Cash</td><td>100</td></tr>
                      <tr><td>Inventory</td><td>50</td></tr>
                    </table>
                    """;
            ParseResult result = parse(html);
            String text = result.getSegments().get(0).text();

            assertThat(text).contains("| Category | Item | Value |");
            assertThat(text).contains("| Assets | Cash | 100 |");
            assertThat(text).contains("|  | Inventory | 50 |");
        }

        @Test
        void escapesPipeCharacters() {
            String html = """
                    <table>
                      <tr><th>Expr</th></tr>
                      <tr><td>a | b</td></tr>
                    </table>
                    """;
            ParseResult result = parse(html);
            String text = result.getSegments().get(0).text();

            assertThat(text).contains("a \\| b");
        }

        @Test
        void preservesNestedTableTextInOwningCell() {
            String html = """
                    <table>
                      <tr><th>Item</th></tr>
                      <tr><td>Outer <table><tr><td>Inner</td></tr></table> End</td></tr>
                    </table>
                    """;
            ParseResult result = parse(html);
            String text = result.getSegments().get(0).text();

            // Nested table text should be collapsed into the owning cell.
            assertThat(text).contains("Outer");
            assertThat(text).contains("Inner");
            assertThat(text).contains("End");
            // Should not render a second separate table.
            int pipeCount = countChar(text, '|');
            // Only one table's worth of pipes.
            assertThat(pipeCount).isLessThan(15);
        }
    }

    // -----------------------------------------------------------------------
    // List rendering
    // -----------------------------------------------------------------------

    @Test
    void rendersUnorderedList() {
        String html = """
                <ul>
                  <li>First</li>
                  <li>Second</li>
                  <li>Third</li>
                </ul>
                """;
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("- First");
        assertThat(text).contains("- Second");
        assertThat(text).contains("- Third");
    }

    @Test
    void rendersOrderedList() {
        String html = """
                <ol>
                  <li>Step one</li>
                  <li>Step two</li>
                </ol>
                """;
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("1. Step one");
        assertThat(text).contains("2. Step two");
    }

    // -----------------------------------------------------------------------
    // Noise stripping
    // -----------------------------------------------------------------------

    @Test
    void stripsScriptAndStyle() {
        String html = """
                <html><body>
                <script>var x = 1;</script>
                <style>.cls { color: red; }</style>
                <p>Visible text</p>
                </body></html>
                """;
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("Visible text");
        assertThat(text).doesNotContain("var x = 1");
        assertThat(text).doesNotContain("color: red");
    }

    @Test
    void stripsNavAndFooter() {
        String html = """
                <html><body>
                <nav>Menu links</nav>
                <main>Main content here</main>
                <footer>Copyright info</footer>
                </body></html>
                """;
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("Main content here");
        assertThat(text).doesNotContain("Menu links");
        assertThat(text).doesNotContain("Copyright info");
    }

    // -----------------------------------------------------------------------
    // Inline XBRL taxonomy fragment text preservation
    // -----------------------------------------------------------------------

    @Test
    void preservesXbrlTextContent() {
        String html = """
                <html><body>
                <p>Revenue: <ix:nonFraction name="Revenue" unit="USD">1000000</ix:nonFraction></p>
                <p>Entity: <ix:nonNumeric name="EntityName">Acme Corp</ix:nonNumeric></p>
                </body></html>
                """;
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        // jsoup treats unknown tags as inline elements — text content is preserved.
        assertThat(text).contains("1000000");
        assertThat(text).contains("Acme Corp");
    }

    // -----------------------------------------------------------------------
    // Metadata — contentFormat and parserType
    // -----------------------------------------------------------------------

    @Test
    void setsMarkdownContentFormat() {
        String html = "<html><body><p>Hello</p></body></html>";
        ParseResult result = parse(html);
        Map<String, Object> metadata = result.getSegments().get(0).metadata();

        assertThat(metadata).containsEntry("contentFormat", "MARKDOWN");
        assertThat(metadata).containsEntry("parserType", "html-jsoup");
        assertThat(metadata).containsEntry("htmlSourceLength", html.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void doesNotSetSegmentSourceOffsets() {
        String html = "<html><body><p>Hello</p></body></html>";
        ParseResult result = parse(html);
        Map<String, Object> metadata = result.getSegments().get(0).metadata();

        assertThat(metadata).doesNotContainKey("sourceStart");
        assertThat(metadata).doesNotContainKey("sourceEnd");
    }

    // -----------------------------------------------------------------------
    // Fallback — zero-byte rejection
    // -----------------------------------------------------------------------

    @Test
    void rejectsZeroByteHtml() {
        ParseResult result = parser.parse(emptySupplier(), "text/html", Map.of());

        assertThat(result.getQualityLevel()).isEqualTo(QualityLevel.REJECTED);
        assertThat(result.getSegments()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Fallback — valid short HTML accepted (not fallback)
    // -----------------------------------------------------------------------

    @Test
    void acceptsValidShortHtml() {
        String html = "<html><body><p>Hi</p></body></html>";
        ParseResult result = parse(html);

        assertThat(result.getQualityLevel()).isNotEqualTo(QualityLevel.REJECTED);
        assertThat(result.getParserType()).isEqualTo("Html");
        String text = result.getSegments().get(0).text();
        assertThat(text).contains("Hi");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentFormat", "MARKDOWN");
    }

    @Test
    void fallsBackForNearEmptyHtmlOverOneKb() {
        String html = "<html><body><p>Short fallback text</p><!--"
                + "x".repeat(1500)
                + "--></body></html>";

        ParseResult result = parse(html);

        assertThat(result.getQualityLevel()).isNotEqualTo(QualityLevel.REJECTED);
        assertThat(result.getParserType()).isEqualTo(ParserType.TIKA.getType());
        assertThat(result.getDiagnostics())
                .containsEntry("htmlFallback", true)
                .containsKey("fallbackReason");
        assertThat(String.valueOf(result.getDiagnostics().get("fallbackReason"))).contains("near-empty");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("Short fallback text");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentFormat", "PLAIN")
                .containsEntry("parserType", ParserType.TIKA.getType());
    }

    // -----------------------------------------------------------------------
    // Fallback — noise-only short HTML accepted
    // -----------------------------------------------------------------------

    @Test
    void rejectsNoiseOnlyShortHtmlWhenTikaAlsoEmpty() {
        // Small HTML with only script content — jsoup strips script before conversion,
        // output is empty → Tika fallback attempted. Tika also treats script as
        // non-rendered content, producing empty output → REJECTED.
        String html = "<html><body><script>var x = 1;</script></body></html>";
        ParseResult result = parse(html);

        // Both jsoup (stripped script) and Tika (non-rendered script) produce no text.
        assertThat(result.getQualityLevel()).isEqualTo(QualityLevel.REJECTED);
    }

    // -----------------------------------------------------------------------
    // Fallback — both parsers empty → REJECTED
    // -----------------------------------------------------------------------

    @Test
    void rejectsWhenBothParsersEmpty() {
        // HTML with only script tags → jsoup strips → empty → Tika also likely empty
        String html = "<html><head><script></script></head><body></body></html>";
        ParseResult result = parse(html);

        assertThat(result.getQualityLevel()).isEqualTo(QualityLevel.REJECTED);
    }

    // -----------------------------------------------------------------------
    // Inline formatting
    // -----------------------------------------------------------------------

    @Test
    void preservesBoldAndItalic() {
        String html = "<html><body><p>This is <strong>bold</strong> and <em>italic</em>.</p></body></html>";
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("**bold**");
        assertThat(text).contains("*italic*");
    }

    @Test
    void preservesLinks() {
        String html = "<html><body><p><a href=\"https://example.com\">Example</a></p></body></html>";
        ParseResult result = parse(html);
        String text = result.getSegments().get(0).text();

        assertThat(text).contains("[Example](https://example.com)");
    }

    // -----------------------------------------------------------------------
    // Committed fixture tests
    // -----------------------------------------------------------------------

    @Nested
    class FixtureTests {

        private byte[] loadFixtureBytes(String name) throws Exception {
            try (InputStream is = getClass().getResourceAsStream("/html-parser/" + name)) {
                assertThat(is).as("fixture " + name).isNotNull();
                return is.readAllBytes();
            }
        }

        private String loadFixture(String name) throws Exception {
            return new String(loadFixtureBytes(name), StandardCharsets.UTF_8);
        }

        private String sha256(byte[] data) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        }

        private JsonNode fixtureManifestEntry(String filename) throws Exception {
            JsonNode manifest = objectMapper.readTree(loadFixtureBytes("fixtures-manifest.json"));
            for (JsonNode fixture : manifest.path("fixtures")) {
                if (filename.equals(fixture.path("filename").asText())) {
                    return fixture;
                }
            }
            throw new AssertionError("fixture manifest missing entry for " + filename);
        }

        private String chunkWithMarkdownChunker(String markdown) {
            StructureAwareMarkdownChunker chunker = new StructureAwareMarkdownChunker(objectMapper);
            ReflectionTestUtils.setField(chunker, "targetChars", 1200);
            ReflectionTestUtils.setField(chunker, "maxChars", 2000);
            ReflectionTestUtils.setField(chunker, "minChars", 1);
            ReflectionTestUtils.setField(chunker, "overlapChars", 0);
            return chunker.chunk(markdown).stream()
                    .map(KnowledgeChunkDraft::content)
                    .collect(Collectors.joining("\n"));
        }

        @Test
        void secFixtureParsesSuccessfully() throws Exception {
            String fixtureName = "sec-edgar-10-k-inline-xbrl.html";
            JsonNode manifestEntry = fixtureManifestEntry(fixtureName);
            byte[] fixtureBytes = loadFixtureBytes(fixtureName);
            assertThat(sha256(fixtureBytes)).isEqualTo(manifestEntry.path("sha256").asText());
            assertThat(manifestEntry.path("status").asText()).isEqualTo("public-data");

            String html = new String(fixtureBytes, StandardCharsets.UTF_8);
            ParseResult result = parser.parse(supplierOf(html), "text/html", Map.of());

            assertThat(result.getQualityLevel()).isNotEqualTo(QualityLevel.REJECTED);
            assertThat(result.getParserType()).isEqualTo("Html");
            assertThat(result.getSegments()).hasSize(1);
            assertThat(result.getSegments().get(0).metadata())
                    .containsEntry("contentFormat", "MARKDOWN");

            String text = result.getSegments().get(0).text();
            assertThat(text).contains("|");
            assertThat(text).contains("Net sales", "383,285", "Total assets", "352,583");

            String chunkedText = chunkWithMarkdownChunker(text);
            assertThat(chunkedText).contains("Net sales", "383,285", "Total assets", "352,583");
        }

        @Test
        void w3cFixtureParsesSuccessfully() throws Exception {
            String fixtureName = "w3c-specification.html";
            JsonNode manifestEntry = fixtureManifestEntry(fixtureName);
            byte[] fixtureBytes = loadFixtureBytes(fixtureName);
            assertThat(sha256(fixtureBytes)).isEqualTo(manifestEntry.path("sha256").asText());

            String html = new String(fixtureBytes, StandardCharsets.UTF_8);
            ParseResult result = parser.parse(supplierOf(html), "text/html", Map.of());

            assertThat(result.getQualityLevel()).isNotEqualTo(QualityLevel.REJECTED);
            assertThat(result.getParserType()).isEqualTo("Html");
            assertThat(result.getSegments()).hasSize(1);
            assertThat(result.getSegments().get(0).metadata())
                    .containsEntry("contentFormat", "MARKDOWN");

            String text = result.getSegments().get(0).text();
            // WCAG spec should contain headings.
            assertThat(text).containsPattern("#");
            assertThat(text.length()).isGreaterThan(500);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
