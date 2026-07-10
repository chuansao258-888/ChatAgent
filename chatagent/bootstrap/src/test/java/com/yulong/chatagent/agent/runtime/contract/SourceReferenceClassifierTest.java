package com.yulong.chatagent.agent.runtime.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 tests for {@link SourceReferenceClassifier}.
 *
 * <p>Table-driven positive/negative cases for session-file detection, aligned
 * with the authoritative {@code AgentThinkingEngine} keyword gate and the
 * {@code FileTypeDetector} supported-extension set. Covers the P2 round-2
 * finding that natural upload phrasings ("uploaded a file", "my file") were
 * missed and that extensions must match the real supported set.</p>
 */
class SourceReferenceClassifierTest {

    private final SourceReferenceClassifier classifier = ContractTestSupport.classifier();

    @ParameterizedTest
    @ValueSource(strings = {
            "I uploaded a file",
            "I just uploaded the spreadsheet",
            "summarize my file",
            "the uploaded report",
            "my uploaded spreadsheet",
            "report.xlsx",
            "data.csv",
            "notes.html",
            "photo.webp",
            "scan.bmp",
            "Compare the policy with my uploaded spreadsheet report.xlsx.",
            "我上传了文件",
            "这个附件",
            "总结一下上传的文档"
    })
    void shouldClassifyAsSessionFile(String text) {
        SourceReferenceClassifier.SourceClassification c = classifier.classify(text);
        assertThat(c.sessionFile())
                .as("should detect session file: %s", text)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I file a tax return?",
            "I excel at Java",
            "my profile",
            "hello there",
            "what is the weather today"  // currentness/web, but NOT a session file
    })
    void shouldNotClassifyAsSessionFile(String text) {
        SourceReferenceClassifier.SourceClassification c = classifier.classify(text);
        assertThat(c.sessionFile())
                .as("should NOT detect session file: %s", text)
                .isFalse();
    }

    @Test
    void shouldNotMatchLegacyPptOrXlsExtensions() {
        // .ppt and .xls are rejected by FileTypeDetector (legacy formats); the
        // classifier should not treat them as supported session-file references.
        SourceReferenceClassifier.SourceClassification c = classifier.classify("see report.ppt");
        // Note: "report.ppt" does not match the supported-extension pattern, but
        // it also should not false-trigger. Assert it is not detected as a file.
        assertThat(c.sessionFile()).isFalse();
    }

    @Test
    void shouldExtractBothEnglishComparisonOperands() {
        SourceReferenceClassifier.SourceClassification c = classifier.classify("Compare policy A with policy B");
        assertThat(c.comparisonTargets())
                .contains("policy A", "policy B");
    }

    @Test
    void shouldExtractBothChineseComparisonOperands() {
        SourceReferenceClassifier.SourceClassification c = classifier.classify("比较政策A和政策B");
        // Both operands should be extracted (政策A and 政策B), covering the P1 round-2 finding.
        assertThat(c.comparisonTargets())
                .anyMatch(t -> t.contains("政策A"))
                .anyMatch(t -> t.contains("政策B"));
    }

    @Test
    void shouldDeriveFileSourceForPassthroughUploadReference() {
        // P2: a passthrough turn (no intent) naming an upload → FILE.
        SourceReferenceClassifier.SourceClassification c = classifier.classify("summarize my uploaded file");
        SourceNeed need = classifier.deriveSourceNeed(c, null);
        assertThat(need).isEqualTo(SourceNeed.FILE);
    }

    @Test
    void shouldDeriveWebSourceForCurrentnessWithoutKb() {
        SourceReferenceClassifier.SourceClassification c = classifier.classify("latest news");
        SourceNeed need = classifier.deriveSourceNeed(c, null);
        assertThat(need).isEqualTo(SourceNeed.WEB);
        assertThat(classifier.deriveTimeSensitivity(c)).isEqualTo(TimeSensitivity.CURRENT);
    }

    @Test
    void shouldExtractFilenameAsFileReference() {
        assertThat(classifier.extractFileReference("summarize report.xlsx"))
                .isEqualTo("report.xlsx");
    }

    @Test
    void shouldExtractUploadNounAsFileReferenceWhenNoFilename() {
        // When there is no filename, the most specific file reference is the upload+noun phrase.
        assertThat(classifier.extractFileReference("summarize the uploaded spreadsheet"))
                .contains("spreadsheet");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How does monetary policy affect inflation?",
            "What is price elasticity?",
            "Explain version control systems",
            "How is a credit score calculated?",
            "What is the stock market?"
    })
    void shouldNotMisclassifyContentNounsAsSourceOrCurrentness(String text) {
        // P2 round-3: bare content nouns (policy/price/version/score/stock) must NOT
        // trigger KB or WEB/CURRENT classification. These are ordinary knowledge questions.
        SourceReferenceClassifier.SourceClassification c = classifier.classify(text);
        SourceNeed need = classifier.deriveSourceNeed(c, null);
        assertThat(need)
                .as("should be NONE for content-noun question: %s", text)
                .isEqualTo(SourceNeed.NONE);
        assertThat(c.currentness())
                .as("should not detect currentness for: %s", text)
                .isFalse();
    }

    @Test
    void shouldClassifyCurrentnessOnlyWithTemporalQualifier() {
        // "latest price" / "current version" qualify; bare "price"/"version" do not.
        assertThat(classifier.classify("latest price of the stock").currentness()).isTrue();
        assertThat(classifier.classify("current version of the API").currentness()).isTrue();
        assertThat(classifier.classify("what is price elasticity").currentness()).isFalse();
    }

    @Test
    void shouldNotClassifyResetNowAsWeb() {
        // ATC-PLAN-F01: "reset it now" must NOT produce WEB source — "now" is excluded.
        SourceReferenceClassifier.SourceClassification c = classifier.classify("reset it now");
        assertThat(c.currentness()).isFalse();
        assertThat(classifier.deriveSourceNeed(c, null)).isEqualTo(SourceNeed.NONE);
    }

    @Test
    void shouldNotClassifySharedConcernAsFile() {
        // ATC-PLAN-F01: "I shared my concern" must NOT produce FILE — "shared" alone is too broad.
        SourceReferenceClassifier.SourceClassification c = classifier.classify("I shared my concern");
        assertThat(c.sessionFile()).isFalse();
        assertThat(classifier.deriveSourceNeed(c, null)).isEqualTo(SourceNeed.NONE);
    }

    @Test
    void shouldPreferFileOverWebWhenBothPresent() {
        // ATC-PLAN-F01: an explicit uploaded-file reference with a temporal word stays FILE.
        SourceReferenceClassifier.SourceClassification c = classifier.classify("summarize the uploaded file from today");
        assertThat(classifier.deriveSourceNeed(c, null)).isEqualTo(SourceNeed.FILE);
    }

    @Test
    void shouldMapWebQueryToWebSearchNotNone() {
        // ATC-PLAN-F01: WEB source need produces a WEB_SEARCH query, not NONE.
        // Verified at the QueryPlanBuilder level in QueryPlanBuilderTest; here we check
        // the classifier does not mis-route a clear currentness request.
        SourceReferenceClassifier.SourceClassification c = classifier.classify("latest news on the product");
        assertThat(classifier.deriveSourceNeed(c, null)).isEqualTo(SourceNeed.WEB);
    }
}
