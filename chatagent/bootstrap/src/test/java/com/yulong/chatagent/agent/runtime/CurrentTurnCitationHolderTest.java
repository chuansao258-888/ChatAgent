package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.rag.model.RagSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentTurnCitationHolderTest {

    private CurrentTurnCitationHolder currentTurnCitationHolder;

    @BeforeEach
    void setUp() {
        currentTurnCitationHolder = new CurrentTurnCitationHolder();
    }

    @Test
    void shouldStoreAndTakeCitationsPerSessionTurnKey() {
        List<CitationMetadata> citations = List.of(citation("doc-1"));

        currentTurnCitationHolder.put("session-1", "turn-1", citations);

        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).containsExactlyElementsOf(citations);
        assertThat(currentTurnCitationHolder.take("session-1", "turn-1")).containsExactlyElementsOf(citations);
        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
    }

    @Test
    void shouldIgnoreInvalidKeysAndClearEmptyValues() {
        currentTurnCitationHolder.put("session-1", "turn-1", List.of(citation("doc-1")));
        currentTurnCitationHolder.put("session-1", "turn-1", List.of());
        currentTurnCitationHolder.put(" ", "turn-2", List.of(citation("doc-2")));

        assertThat(currentTurnCitationHolder.peek("session-1", "turn-1")).isEmpty();
        assertThat(currentTurnCitationHolder.peek(" ", "turn-2")).isEmpty();
    }

    @Test
    void shouldAppendNumberedBatchesWithoutDroppingDuplicateMetadataSlots() {
        CitationMetadata first = citation("doc-1");
        CitationMetadata duplicate = citation("doc-1");

        int firstBatchNumber = currentTurnCitationHolder.appendAndGetFirstCitationNumber(
                "session-1", "turn-1", List.of(first));
        int secondBatchNumber = currentTurnCitationHolder.appendAndGetFirstCitationNumber(
                "session-1", "turn-1", List.of(duplicate, citation("doc-2")));

        assertThat(firstBatchNumber).isEqualTo(1);
        assertThat(secondBatchNumber).isEqualTo(2);
        assertThat(currentTurnCitationHolder.take("session-1", "turn-1"))
                .extracting(CitationMetadata::documentId)
                .containsExactly("doc-1", "doc-1", "doc-2");
    }

    private CitationMetadata citation(String documentId) {
        return new CitationMetadata(
                RagSourceType.KNOWLEDGE_BASE,
                "kb-1",
                documentId,
                "doc.pdf",
                "Section",
                1,
                "snippet",
                0.91d,
                "reranker",
                false
        );
    }
}
