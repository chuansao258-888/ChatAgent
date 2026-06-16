package com.yulong.chatagent.eval.v2.docingestion;

import com.yulong.chatagent.eval.v2.docingestion.ReferenceRebinder.NewChunk;
import com.yulong.chatagent.eval.v2.docingestion.ReferenceRebinder.RebindInput;
import com.yulong.chatagent.eval.v2.docingestion.ReferenceRebinder.RebindOutput;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReferenceRebinder}.
 *
 * <p>Covers one-chunk binding, multi-chunk window selection, same-source enforcement,
 * missing/tied cases, narrow input records, deterministic ordering, and
 * hash utilities.</p>
 */
class ReferenceRebinderTest {

    private static final String SRC_URL = "https://example.com/doc.html";
    private static final String SRC_SHA = "abc123";
    private static final String SRC_FILENAME = "doc.html";

    private static RebindInput input(String sampleId, String referenceContent) {
        return new RebindInput(sampleId, SRC_URL, SRC_SHA, SRC_FILENAME,
                "SEC_HTML", "calibration", "old-chunk-1", referenceContent);
    }

    private static NewChunk chunk(String chunkId, int chunkIndex, String content) {
        return new NewChunk(SRC_URL, SRC_SHA, SRC_FILENAME, chunkId, chunkIndex, content);
    }

    private static NewChunk otherSourceChunk(String chunkId, int chunkIndex, String content) {
        return new NewChunk("https://other.com/file.pdf", "def456", "file.pdf",
                chunkId, chunkIndex, content);
    }

    // -----------------------------------------------------------------------
    // One-chunk bind
    // -----------------------------------------------------------------------

    @Nested
    class OneChunkBind {

        @Test
        void bindsWhenSingleChunkContainsOldContent() {
            RebindInput in = input("s1", "Revenue was 383 million");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "The company reported Revenue was 383 million in Q3.")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).sampleId()).isEqualTo("s1");
            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c1");
            assertThat(out.get(0).auditWindowChunkIds()).containsExactly("c1");
            assertThat(out.get(0).windowLength()).isEqualTo(1);
        }

        @Test
        void bindsWithNormalization() {
            RebindInput in = input("s2", "Net Sales: $383,285");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "The Net Sales: $383,285 figure was reported.")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c1");
        }

        @Test
        void bindsLinkedImageEvidenceByAltText() {
            RebindInput in = input("s2a",
                    "[![The query language for modern APIs](./assets/banner.png)](https://graphql.org/)");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0,
                            "# GraphQL\n[![The query language for modern APIs](./assets/banner.png)]"
                                    + "(https://graphql.org/)")
            );

            RebindOutput output = ReferenceRebinder.rebind(List.of(in), inventory).get(0);

            assertThat(output.status()).isEqualTo("bound");
            assertThat(output.newReferenceChunkId()).isEqualTo("c1");
        }
    }

    // -----------------------------------------------------------------------
    // Multi-chunk window
    // -----------------------------------------------------------------------

    @Nested
    class MultiChunkWindow {

        @Test
        void selectsShortestWindow() {
            RebindInput in = input("s3", "Total assets 352583");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Revenue was 383285 and"),
                    chunk("c2", 1, "Total assets 352583 for the year"),
                    chunk("c3", 2, "Additional notes follow here")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c2");
            assertThat(out.get(0).auditWindowChunkIds()).containsExactly("c2");
            assertThat(out.get(0).windowLength()).isEqualTo(1);
        }

        @Test
        void selectsWindowSpanningTwoChunks() {
            RebindInput in = input("s4", "Revenue was 383285 and Total assets were 352583");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Revenue was 383285 and"),
                    chunk("c2", 1, "Total assets were 352583 for the year with Revenue was 383285 repeated"),
                    chunk("c3", 2, "Additional notes follow here")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).auditWindowChunkIds()).containsExactly("c1", "c2");
            assertThat(out.get(0).windowLength()).isEqualTo(2);
            // c2 has higher token-multiset coverage of old content (contains almost all tokens).
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c2");
        }

        @Test
        void prefersShorterWindowOverLonger() {
            RebindInput in = input("s5", "Unique target phrase");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Some header content before the target"),
                    chunk("c2", 1, "Unique target phrase appears here"),
                    chunk("c3", 2, "Unrelated follow-up material"),
                    chunk("c4", 3, "More trailing content")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c2");
            assertThat(out.get(0).windowLength()).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Same-source enforcement
    // -----------------------------------------------------------------------

    @Nested
    class SameSourceEnforcement {

        @Test
        void doesNotBindAcrossDifferentSource() {
            RebindInput in = input("s6", "Revenue was 383 million");
            List<NewChunk> inventory = List.of(
                    otherSourceChunk("x1", 0, "Revenue was 383 million reported in the filing")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("missing");
            assertThat(out.get(0).newReferenceChunkId()).isNull();
        }

        @Test
        void bindsOnlyFromMatchingSource() {
            RebindInput in = input("s7", "Revenue was 383 million");
            List<NewChunk> inventory = List.of(
                    otherSourceChunk("x1", 0, "Revenue was 383 million but wrong source"),
                    chunk("c1", 0, "Revenue was 383 million in the correct document")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c1");
        }
    }

    // -----------------------------------------------------------------------
    // Missing / blank / tied cases
    // -----------------------------------------------------------------------

    @Nested
    class MissingAndTied {

        @Test
        void missingWhenNoChunksExist() {
            RebindInput in = input("s8", "Some content here");
            List<NewChunk> inventory = List.of();

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("missing");
        }

        @Test
        void missingWhenContentNotFound() {
            RebindInput in = input("s9", "This specific text does not appear");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Completely unrelated content about other topics")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("missing");
        }

        @Test
        void missingWhenOldContentIsBlank() {
            RebindInput in = new RebindInput("s10", SRC_URL, SRC_SHA, SRC_FILENAME,
                    "SEC_HTML", "calibration", "old-chunk-1", "");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Some content")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("missing");
        }

        @Test
        void missingWhenOldContentIsOnlyNonAlphanumeric() {
            RebindInput in = new RebindInput("s11", SRC_URL, SRC_SHA, SRC_FILENAME,
                    "SEC_HTML", "calibration", "old-chunk-1", "--- !!! ...");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Some content")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("missing");
        }

        @Test
        void choosesEarliestWhenTwoShortestWindowsTie() {
            // Two windows of equal length (1) both contain the old content.
            RebindInput in = input("s12", "duplicated target phrase");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "duplicated target phrase in first chunk"),
                    chunk("c2", 1, "duplicated target phrase in second chunk")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c1");
            assertThat(out.get(0).tieBreak()).isEqualTo("earliest-window");
        }

        @Test
        void choosesEarliestWhenPrimaryCoverageTied() {
            // Two chunks split old content evenly — both have equal token-multiset coverage.
            RebindInput in = input("s13", "alpha beta gamma delta");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "alpha beta"),
                    chunk("c2", 1, "gamma delta")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isEqualTo("c1");
            assertThat(out.get(0).tieBreak()).isEqualTo("earliest-primary");
        }
    }

    // -----------------------------------------------------------------------
    // HTML semantic fallback
    // -----------------------------------------------------------------------

    @Nested
    class HtmlSemanticFallback {

        @Test
        void bindsHtmlAtCoverageThreshold() {
            RebindInput in = input("s13a",
                    "one two three four five six seven eight nine ten "
                            + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0,
                            "one two three four five six seven eight nine ten "
                                    + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen")
            );

            RebindOutput output = ReferenceRebinder.rebind(List.of(in), inventory).get(0);

            assertThat(output.status()).isEqualTo("bound");
            assertThat(output.matchMethod()).isEqualTo("html-token-coverage");
            assertThat(output.matchCoverage()).isEqualTo(0.95);
        }

        @Test
        void rejectsHtmlBelowCoverageThreshold() {
            RebindInput in = input("s13b",
                    "one two three four five six seven eight nine ten "
                            + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0,
                            "one two three four five six seven eight nine ten "
                                    + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen")
            );

            RebindOutput output = ReferenceRebinder.rebind(List.of(in), inventory).get(0);

            assertThat(output.status()).isEqualTo("missing");
        }

        @Test
        void nonHtmlFormatRemainsExactOnly() {
            String content = "one two three four five six seven eight nine ten "
                    + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty";
            RebindInput in = new RebindInput("s13c", "https://example.com/file.pdf", SRC_SHA, "file.pdf",
                    "PDF", "calibration", "old-chunk-1", content);
            NewChunk pdfChunk = new NewChunk("https://example.com/file.pdf", SRC_SHA, "file.pdf",
                    "c1", 0, content.replace(" twenty", ""));

            RebindOutput output = ReferenceRebinder.rebind(List.of(in), List.of(pdfChunk)).get(0);

            assertThat(output.status()).isEqualTo("missing");
        }

        @Test
        void markdownBackedWebSourceRemainsExactOnly() {
            String content = "one two three four five six seven eight nine ten "
                    + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty";
            RebindInput in = new RebindInput("s13d", "https://example.com/file.md", SRC_SHA, "file.md",
                    "WEB_MD", "calibration", "old-chunk-1", content);
            NewChunk markdownChunk = new NewChunk("https://example.com/file.md", SRC_SHA, "file.md",
                    "c1", 0, content.replace(" twenty", ""));

            RebindOutput output = ReferenceRebinder.rebind(List.of(in), List.of(markdownChunk)).get(0);

            assertThat(output.status()).isEqualTo("missing");
        }
    }

    // -----------------------------------------------------------------------
    // Narrow input — no query/retrieval/scoring fields
    // -----------------------------------------------------------------------

    @Nested
    class NarrowInput {

        @Test
        void inputRecordHasNoQueryFields() {
            RebindInput in = input("s14", "Some content");

            // RebindInput should only carry source identity + reference content fields.
            assertThat(in.sampleId()).isNotNull();
            assertThat(in.sourceUrl()).isNotNull();
            assertThat(in.sourceSha256()).isNotNull();
            assertThat(in.filename()).isNotNull();
            assertThat(in.format()).isNotNull();
            assertThat(in.split()).isNotNull();
            assertThat(in.oldReferenceChunkId()).isNotNull();
            assertThat(in.referenceContent()).isNotNull();

            // Verify no query/retrieval/scoring fields exist on the record.
            // (This is a compile-time guarantee — the record only has the above fields.)
            assertThat(in.getClass().getRecordComponents()).hasSize(8);
        }

        @Test
        void outputHasExactlyOneReferenceWhenBound() {
            RebindInput in = input("s15", "Revenue was 383 million");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Revenue was 383 million in Q3")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out.get(0).status()).isEqualTo("bound");
            assertThat(out.get(0).newReferenceChunkId()).isNotNull();
            // Single-reference: exactly one new reference chunk ID.
            assertThat(out.get(0).newReferenceChunkId()).isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // Deterministic ordering
    // -----------------------------------------------------------------------

    @Nested
    class DeterministicOrdering {

        @Test
        void outputSortedBySampleId() {
            RebindInput in3 = input("sample-c", "Revenue was 383");
            RebindInput in1 = input("sample-a", "Total assets 352");
            RebindInput in2 = input("sample-b", "Net income 100");

            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Revenue was 383"),
                    chunk("c2", 1, "Total assets 352"),
                    chunk("c3", 2, "Net income 100")
            );

            List<RebindOutput> out = ReferenceRebinder.rebind(List.of(in3, in1, in2), inventory);

            assertThat(out).hasSize(3);
            assertThat(out.get(0).sampleId()).isEqualTo("sample-a");
            assertThat(out.get(1).sampleId()).isEqualTo("sample-b");
            assertThat(out.get(2).sampleId()).isEqualTo("sample-c");
        }

        @Test
        void sameInputProducesSameOutput() {
            RebindInput in = input("s16", "Revenue was 383 million");
            List<NewChunk> inventory = List.of(
                    chunk("c1", 0, "Revenue was 383 million in Q3")
            );

            List<RebindOutput> out1 = ReferenceRebinder.rebind(List.of(in), inventory);
            List<RebindOutput> out2 = ReferenceRebinder.rebind(List.of(in), inventory);

            assertThat(out1.get(0).newReferenceChunkId())
                    .isEqualTo(out2.get(0).newReferenceChunkId());
            assertThat(out1.get(0).auditWindowChunkIds())
                    .isEqualTo(out2.get(0).auditWindowChunkIds());
        }
    }

    // -----------------------------------------------------------------------
    // Token-multiset coverage
    // -----------------------------------------------------------------------

    @Nested
    class TokenMultisetCoverage {

        @Test
        void fullCoverageWhenAllTokensPresent() {
            Map<String, Integer> old = Map.of("revenue", 2, "million", 1);
            Map<String, Integer> chunk = Map.of("revenue", 3, "million", 2);
            double coverage = ReferenceRebinder.tokenMultisetCoverage(old, chunk);
            assertThat(coverage).isEqualTo(1.0);
        }

        @Test
        void partialCoverageWhenSomeTokensMissing() {
            Map<String, Integer> old = Map.of("revenue", 2, "million", 1, "assets", 1);
            Map<String, Integer> chunk = Map.of("revenue", 1, "million", 2);
            double coverage = ReferenceRebinder.tokenMultisetCoverage(old, chunk);
            // min(2,1)=1 + min(1,2)=1 + min(1,0)=0 = 2 / (2+1+1) = 0.5
            assertThat(coverage).isEqualTo(0.5);
        }

        @Test
        void zeroCoverageWhenNoOverlap() {
            Map<String, Integer> old = Map.of("revenue", 1);
            Map<String, Integer> chunk = Map.of("assets", 1);
            double coverage = ReferenceRebinder.tokenMultisetCoverage(old, chunk);
            assertThat(coverage).isEqualTo(0.0);
        }
    }

    // -----------------------------------------------------------------------
    // Token frequencies
    // -----------------------------------------------------------------------

    @Nested
    class TokenFrequencies {

        @Test
        void countsDuplicateTokens() {
            Map<String, Integer> freq = ReferenceRebinder.tokenFrequencies("revenue revenue assets");
            assertThat(freq).containsEntry("revenue", 2);
            assertThat(freq).containsEntry("assets", 1);
        }

        @Test
        void emptyForBlankInput() {
            Map<String, Integer> freq = ReferenceRebinder.tokenFrequencies("");
            assertThat(freq).isEmpty();
        }

        @Test
        void emptyForNullInput() {
            Map<String, Integer> freq = ReferenceRebinder.tokenFrequencies(null);
            assertThat(freq).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Source identity
    // -----------------------------------------------------------------------

    @Nested
    class SourceIdentity {

        @Test
        void combinesUrlSha256Filename() {
            String identity = ReferenceRebinder.sourceIdentity(
                    "https://example.com/doc.html", "abc123", "doc.html");
            assertThat(identity).isEqualTo("https://example.com/doc.html|abc123|doc.html");
        }

        @Test
        void differentSourcesHaveDifferentIdentities() {
            String id1 = ReferenceRebinder.sourceIdentity("url1", "sha1", "file1");
            String id2 = ReferenceRebinder.sourceIdentity("url2", "sha1", "file1");
            assertThat(id1).isNotEqualTo(id2);
        }
    }

    // -----------------------------------------------------------------------
    // Normalized match text
    // -----------------------------------------------------------------------

    @Nested
    class NormalizedMatchText {

        @Test
        void lowercasesAndStripsNonAlphanumeric() {
            String result = ReferenceRebinder.normalizedMatchText("Net Sales: $383,285!");
            assertThat(result).isEqualTo("net sales 383 285");
        }

        @Test
        void returnsEmptyForNull() {
            assertThat(ReferenceRebinder.normalizedMatchText(null)).isEmpty();
        }

        @Test
        void returnsEmptyForEmpty() {
            assertThat(ReferenceRebinder.normalizedMatchText("")).isEmpty();
        }

        @Test
        void returnsEmptyForNonAlphanumericOnly() {
            assertThat(ReferenceRebinder.normalizedMatchText("--- !!! ...")).isEmpty();
        }

        @Test
        void removesLegacyHtmlAndRendererOnlyMarkdown() {
            String result = ReferenceRebinder.normalizedMatchText("""
                    <p>Revenue &amp; income</p>
                    [filing label](https://example.com/source)
                    ![chart](images/chart.png)
                    | Column 1 | Column 2 |
                    | --- | --- |
                    Data
                    """);

            assertThat(result).isEqualTo("revenue income filing label chart data");
        }

        @Test
        void preservesAltTextFromLinkedMarkdownImage() {
            String result = ReferenceRebinder.normalizedMatchText(
                    "[![The query language for modern APIs](./assets/banner.png)](https://graphql.org/)");

            assertThat(result).isEqualTo("the query language for modern apis");
        }
    }
}
