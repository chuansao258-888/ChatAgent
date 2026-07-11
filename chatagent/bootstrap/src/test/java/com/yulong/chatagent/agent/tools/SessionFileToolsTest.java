package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnExecutionContractHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.contract.ContractTestSupport;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.rag.application.FormattedRetrievalPrompt;
import com.yulong.chatagent.rag.application.RagService;
import com.yulong.chatagent.rag.application.RetrievalHitFormatter;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.model.RetrievalHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionFileToolsTest {

    @Mock
    private RagService ragService;

    @Mock
    private RetrievalHitFormatter retrievalHitFormatter;

    @AfterEach
    void tearDown() {
        CurrentChatSessionHolder.clear();
        CurrentTurnHolder.clear();
        CurrentIntentResolutionHolder.clear();
        CurrentTurnExecutionContractHolder.clear();
        CurrentTurnKnowledgeHitHolder.clear();
    }

    @Test
    void shouldPrioritizeSessionFileHitsWhenQueryNamesAttachedFile() {
        CurrentChatSessionHolder.set("session-1");
        CurrentTurnHolder.set("turn-1");
        RetrievalHit kbHit = hit(RagSourceType.KNOWLEDGE_BASE, "priority-kb.md");
        RetrievalHit sessionHit = hit(RagSourceType.SESSION_FILE, "priority-session.txt");
        when(ragService.similaritySearchBySession(eq("session-1"), any(), isNull()))
                .thenReturn(List.of(kbHit, sessionHit));
        when(retrievalHitFormatter.formatWithCitations(any()))
                .thenReturn(new FormattedRetrievalPrompt("prompt", List.of()));
        SessionFileTools tools = new SessionFileTools(
                ragService,
                retrievalHitFormatter,
                new CurrentTurnCitationHolder());

        String result = tools.knowledgeQuery(
                "In the file I just attached, priority-session.txt, what's the session-only marker?");

        assertThat(result).isEqualTo("prompt");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievalHit>> hitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalHitFormatter).formatWithCitations(hitsCaptor.capture());
        assertThat(hitsCaptor.getValue())
                .extracting(RetrievalHit::sourceType)
                .containsExactly(RagSourceType.SESSION_FILE, RagSourceType.KNOWLEDGE_BASE);
    }

    @Test
    void shouldUseQueryPlanSourceForContractRetrieval() {
        CurrentChatSessionHolder.set("session-1");
        CurrentTurnHolder.set("turn-1");
        TurnExecutionContract contract = ContractTestSupport.contractBuilder().build(
                null, "summarize uploaded report.pdf", "summarize uploaded report.pdf",
                AgentExecutionMode.REACT);
        CurrentTurnExecutionContractHolder.set(contract);
        when(ragService.similaritySearchBySession(
                "session-1",
                "summarize uploaded report.pdf",
                null,
                contract.retrieval(),
                RetrievalSource.SESSION_FILES))
                .thenReturn(List.of());
        when(retrievalHitFormatter.formatWithCitations(List.of()))
                .thenReturn(new FormattedRetrievalPrompt("No evidence found.", List.of()));
        SessionFileTools tools = new SessionFileTools(
                ragService, retrievalHitFormatter, new CurrentTurnCitationHolder());

        String result = tools.knowledgeQuery("summarize uploaded report.pdf");

        assertThat(result).isEqualTo("No evidence found.");
        verify(ragService).similaritySearchBySession(
                "session-1",
                "summarize uploaded report.pdf",
                null,
                contract.retrieval(),
                RetrievalSource.SESSION_FILES);
    }

    private RetrievalHit hit(RagSourceType sourceType, String documentName) {
        return new RetrievalHit(
                sourceType,
                "source-1",
                documentName,
                documentName,
                0,
                "chunk[0]",
                "content",
                "context",
                0.1d,
                "retrieval",
                false);
    }
}
