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
import com.yulong.chatagent.rag.model.RetrievalExecutionResult;
import com.yulong.chatagent.rag.model.RetrievalHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        RetrievalExecutionResult executionResult = RetrievalExecutionResult.noHit(
                RetrievalSource.SESSION_FILES,
                List.of(RetrievalSource.SESSION_FILES),
                false,
                List.of());
        when(ragService.similaritySearchByContract(
                eq("session-1"),
                eq("summarize uploaded report.pdf"),
                any(com.yulong.chatagent.agent.runtime.contract.RetrievalPlan.class),
                eq(RetrievalSource.SESSION_FILES)))
                .thenReturn(executionResult);
        when(retrievalHitFormatter.formatExecutionResult(executionResult))
                .thenReturn(new FormattedRetrievalPrompt("No evidence found.", List.of()));
        SessionFileTools tools = new SessionFileTools(
                ragService, retrievalHitFormatter, new CurrentTurnCitationHolder());

        String result = tools.knowledgeQuery("summarize uploaded report.pdf");

        assertThat(result).isEqualTo("No evidence found.");
        verify(ragService).similaritySearchByContract(
                eq("session-1"),
                eq("summarize uploaded report.pdf"),
                any(com.yulong.chatagent.agent.runtime.contract.RetrievalPlan.class),
                eq(RetrievalSource.SESSION_FILES));
    }

    @Test
    void shouldKeepSameTextContractRoutesInSeparateKbScopes() {
        CurrentChatSessionHolder.set("session-1");
        CurrentTurnHolder.set("turn-1");
        TurnExecutionContract contract = multiContract();
        CurrentTurnExecutionContractHolder.set(contract);
        RetrievalExecutionResult noHit = RetrievalExecutionResult.noHit(
                RetrievalSource.INTENT_KB,
                List.of(RetrievalSource.INTENT_KB),
                false,
                List.of());
        when(ragService.similaritySearchByContract(
                eq("session-1"), eq("compare policies"),
                any(com.yulong.chatagent.agent.runtime.contract.RetrievalPlan.class),
                eq(RetrievalSource.INTENT_KB)))
                .thenReturn(noHit);
        when(retrievalHitFormatter.formatExecutionResult(noHit))
                .thenReturn(new FormattedRetrievalPrompt("No evidence.", List.of()));
        SessionFileTools tools = new SessionFileTools(
                ragService, retrievalHitFormatter, new CurrentTurnCitationHolder());

        tools.knowledgeQuery("untrusted replacement", "q0");
        tools.knowledgeQuery("untrusted replacement", "q1");

        ArgumentCaptor<com.yulong.chatagent.agent.runtime.contract.RetrievalPlan> planCaptor =
                ArgumentCaptor.forClass(com.yulong.chatagent.agent.runtime.contract.RetrievalPlan.class);
        verify(ragService, org.mockito.Mockito.times(2)).similaritySearchByContract(
                eq("session-1"), eq("compare policies"), planCaptor.capture(),
                eq(RetrievalSource.INTENT_KB));
        assertThat(planCaptor.getAllValues()).extracting(
                com.yulong.chatagent.agent.runtime.contract.RetrievalPlan::scopedKbIds)
                .containsExactly(List.of("kb-a"), List.of("kb-b"));
    }

    @Test
    void shouldRejectUnknownRouteKeyWithoutCallingRag() {
        CurrentChatSessionHolder.set("session-1");
        CurrentTurnHolder.set("turn-1");
        CurrentTurnExecutionContractHolder.set(multiContract());
        SessionFileTools tools = new SessionFileTools(
                ragService, retrievalHitFormatter, new CurrentTurnCitationHolder());

        assertThatThrownBy(() -> tools.knowledgeQuery("compare policies", "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown retrieval routeKey");
        verifyNoInteractions(ragService);
    }

    private TurnExecutionContract multiContract() {
        List<String> ids = List.of("a", "b");
        List<com.yulong.chatagent.intent.application.IntentResolution> resolutions = ids.stream()
                .map(id -> new com.yulong.chatagent.intent.application.IntentResolution(
                        com.yulong.chatagent.intent.model.IntentKind.KB,
                        List.of(com.yulong.chatagent.support.dto.IntentNodeDTO.builder()
                                .id(id).name(id).build()),
                        List.of("kb-" + id),
                        com.yulong.chatagent.intent.model.ScopePolicy.STRICT,
                        List.of(), null))
                .toList();
        List<com.yulong.chatagent.intent.application.IntentCandidateEvidence> evidence =
                java.util.stream.IntStream.range(0, ids.size())
                        .mapToObj(index -> new com.yulong.chatagent.intent.application.IntentCandidateEvidence(
                                ids.get(index), ids.get(index), 1.0d, 0.0d,
                                index + 1, List.of("test")))
                        .toList();
        var decision = new com.yulong.chatagent.intent.application.IntentDecision(
                com.yulong.chatagent.intent.application.IntentRouteOutcome.MULTI_INTENT,
                "a", List.of("b"), evidence, List.of(),
                com.yulong.chatagent.intent.application.IntentDecisionSource.CLASSIFIER,
                0.9d,
                com.yulong.chatagent.intent.application.ConfidenceStatus.CALIBRATED,
                "v1", List.of("test"));
        var understanding = new com.yulong.chatagent.intent.application.IntentUnderstandingResult(
                decision,
                com.yulong.chatagent.agent.runtime.contract.SourceNeed.KB,
                com.yulong.chatagent.agent.runtime.contract.TimeSensitivity.STATIC,
                com.yulong.chatagent.agent.runtime.contract.ActionRisk.READ_ONLY,
                List.of(com.yulong.chatagent.agent.runtime.contract.IntentLabel.MULTI_INTENT),
                false, false);
        return ContractTestSupport.contractBuilder().buildForRoutes(
                resolutions, "compare policies", "compare policies", AgentExecutionMode.REACT,
                understanding);
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
