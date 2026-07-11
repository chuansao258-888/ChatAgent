package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.runtime.contract.ContractTestSupport;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.model.RetrievalExecutionOutcome;
import com.yulong.chatagent.rag.model.RetrievalExecutionResult;
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

    @Test
    void shouldAggregateSanitizedRetrievalMetadataPerTurnAndConsumeOnce() {
        TurnExecutionContract contract = ContractTestSupport.contractBuilder().build(
                null, "uploaded report", "uploaded report", AgentExecutionMode.REACT);
        currentTurnCitationHolder.recordRetrievalResult(
                "session-1", "turn-1",
                RetrievalExecutionResult.noHit(
                        RetrievalSource.SESSION_FILES,
                        List.of(RetrievalSource.SESSION_FILES), false, List.of()),
                contract);
        currentTurnCitationHolder.recordRetrievalResult(
                "session-1", "turn-1",
                RetrievalExecutionResult.hit(
                        RetrievalSource.SESSION_FILES,
                        List.of(RetrievalSource.SESSION_FILES), false, false,
                        List.of(new com.yulong.chatagent.rag.model.RetrievalHit(
                                RagSourceType.SESSION_FILE, "file-1", "doc-1", "report.pdf",
                                0, "chunk[0]", "content", null, 0.9d, "retrieval", false))),
                contract);

        assertThat(currentTurnCitationHolder.peekRetrievalMetadata("session-1", "turn-2"))
                .isNull();
        assertThat(currentTurnCitationHolder.peekRetrievalMetadata("session-1", "turn-1"))
                .satisfies(metadata -> {
                    assertThat(metadata.retrievalOutcome()).isEqualTo("HIT");
                    assertThat(metadata.retrievalOutcomeDetail()).isEqualTo(RetrievalExecutionOutcome.HIT);
                    assertThat(metadata.actualSources()).containsExactly(RetrievalSource.SESSION_FILES);
                    assertThat(metadata.hitCount()).isEqualTo(1);
                });
        assertThat(currentTurnCitationHolder.takeRetrievalMetadata("session-1", "turn-1"))
                .isNotNull();
        assertThat(currentTurnCitationHolder.takeRetrievalMetadata("session-1", "turn-1"))
                .isNull();
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
