package com.yulong.chatagent.rag.application;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.model.RagSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalHitFormatterTest {

    private final RetrievalHitFormatter formatter = new RetrievalHitFormatter(TestPromptLoader.create());

    @Test
    void shouldRenderStructuredPromptSections() {
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(
                        RagSourceType.SESSION_FILE,
                        "file-1",
                        "file-1",
                        "Employee Handbook.pdf",
                        2,
                        "chunk[2]",
                        "Employees can claim travel expenses within 30 days.",
                        "Travel reimbursement policy",
                        0.92d,
                        "reranker",
                        false
                )
        );

        FormattedRetrievalPrompt rendered = formatter.formatWithCitations(hits);

        assertThat(rendered.promptText()).contains("numbered evidence snippets when answering");
        assertThat(rendered.promptText())
                .contains("directly matches the latest user's topic, entity")
                .contains("Ignore unrelated snippets")
                .contains("this handoff")
                .contains("conversation's project/object as a constraint")
                .contains("other source belongs to the current handoff")
                .contains("Do not quote fields from that other source");
        assertThat(rendered.promptText()).contains("[1] Source: Employee Handbook.pdf [SESSION_FILE] chunk 2");
        assertThat(rendered.promptText()).contains("Section: chunk[2]");
        assertThat(rendered.promptText()).contains("Chunk Context:\nTravel reimbursement policy");
        assertThat(rendered.promptText()).contains("Chunk Content:\nEmployees can claim travel expenses within 30 days.");
        assertThat(rendered.citations()).hasSize(1);
        assertThat(rendered.citations().get(0).documentName()).isEqualTo("Employee Handbook.pdf");
        assertThat(rendered.citations().get(0).chunkIndex()).isEqualTo(2);
        assertThat(rendered.citations().get(0).scoreType()).isEqualTo("reranker");
        assertThat(rendered.citations().get(0).isFallback()).isFalse();
    }

    @Test
    void shouldRenderFallbackWhenNoHits() {
        FormattedRetrievalPrompt rendered = formatter.formatWithCitations(List.of());

        assertThat(rendered.promptText()).isEqualTo("No relevant attached session-file content found.");
        assertThat(rendered.citations()).isEmpty();
    }

    @Test
    void shouldKeepCitationOrderingAlignedWithPromptNumbers() {
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(RagSourceType.KNOWLEDGE_BASE, "kb-1", "doc-1", "Doc A", 1, "A", "Alpha", null, 0.91d, "reranker", false),
                new RetrievalHit(RagSourceType.SESSION_FILE, "file-1", "doc-2", "Doc B", 3, "B", "Beta", null, 0.87d, "reranker", false)
        );

        FormattedRetrievalPrompt rendered = formatter.formatWithCitations(hits);

        assertThat(rendered.promptText()).contains("[1] Source: Doc A [KNOWLEDGE_BASE] chunk 1");
        assertThat(rendered.promptText()).contains("[2] Source: Doc B [SESSION_FILE] chunk 3");
        assertThat(rendered.citations()).extracting(citation -> citation.documentId())
                .containsExactly("doc-1", "doc-2");
    }

    @Test
    void shouldContinueCitationNumbersForLaterRetrievalBatch() {
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(RagSourceType.KNOWLEDGE_BASE, "kb-1", "doc-4", "Doc D", 0, "D", "Delta", null, 0.8d, "reranker", false),
                new RetrievalHit(RagSourceType.KNOWLEDGE_BASE, "kb-1", "doc-5", "Doc E", 0, "E", "Echo", null, 0.7d, "reranker", false)
        );

        FormattedRetrievalPrompt rendered = formatter.formatWithCitations(hits, 4);

        assertThat(rendered.promptText()).contains("[4] Source: Doc D [KNOWLEDGE_BASE] chunk 0");
        assertThat(rendered.promptText()).contains("[5] Source: Doc E [KNOWLEDGE_BASE] chunk 0");
        assertThat(rendered.citations()).extracting(citation -> citation.documentId())
                .containsExactly("doc-4", "doc-5");
    }

    @Test
    void shouldClearCitationsWhenEveryHitIsFilteredOut() {
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(RagSourceType.KNOWLEDGE_BASE, "kb-1", "doc-1", "Doc A", 1, "A", "Filtered evidence", null, null, "filtered", false)
        );

        FormattedRetrievalPrompt rendered = formatter.formatWithCitations(hits);

        assertThat(rendered.promptText()).isEqualTo("No relevant attached session-file content found.");
        assertThat(rendered.citations()).isEmpty();
    }

    @Test
    void shouldDropFilteredMetadataAndRenumberVisibleEvidenceContiguously() {
        List<RetrievalHit> hits = List.of(
                new RetrievalHit(RagSourceType.KNOWLEDGE_BASE, "kb-1", "doc-1", "Doc A", 1, "A", "Filtered evidence", null, null, "filtered", false),
                new RetrievalHit(RagSourceType.SESSION_FILE, "file-1", "doc-2", "Doc B", 3, "B", "Trusted evidence", null, 0.87d, "reranker", false)
        );

        FormattedRetrievalPrompt rendered = formatter.formatWithCitations(hits);

        assertThat(rendered.promptText()).doesNotContain("Filtered evidence");
        assertThat(rendered.promptText()).contains("[1] Source: Doc B [SESSION_FILE] chunk 3");
        assertThat(rendered.promptText()).doesNotContain("[2] Source:");
        assertThat(rendered.promptText()).contains("Trusted evidence");
        assertThat(rendered.citations()).hasSize(1);
        assertThat(rendered.citations()).extracting(citation -> citation.scoreType())
                .containsExactly("reranker");
        assertThat(rendered.citations()).extracting(citation -> citation.documentId())
                .containsExactly("doc-2");
    }
}
