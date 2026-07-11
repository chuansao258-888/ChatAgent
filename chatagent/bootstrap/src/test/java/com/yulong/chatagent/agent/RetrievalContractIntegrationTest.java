package com.yulong.chatagent.agent;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnExecutionContractHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.contract.ContractTestSupport;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.agent.tools.SessionFileTools;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.rag.SearchScopeResolver;
import com.yulong.chatagent.rag.application.RagService;
import com.yulong.chatagent.rag.application.RetrievalHitFormatter;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.model.RetrievalExecutionOutcome;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.retrieve.RetrievalReranker;
import com.yulong.chatagent.rag.retrieve.SessionFileSimilaritySearcher;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalContractIntegrationTest {

    @AfterEach
    void clearHolders() {
        CurrentChatSessionHolder.clear();
        CurrentTurnHolder.clear();
        CurrentIntentResolutionHolder.clear();
        CurrentTurnExecutionContractHolder.clear();
        CurrentTurnKnowledgeHitHolder.clear();
    }

    @Test
    void plainKbIntentExecutesScopedRetrievalThroughRealToolChain() {
        String sessionId = "session-1";
        String turnId = "turn-1";
        String query = "年假怎么申请？";
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leave").name("年假").build()),
                List.of("kb-leave"),
                ScopePolicy.STRICT,
                List.of(),
                null);
        TurnExecutionContract contract = ContractTestSupport.contractBuilder().build(
                resolution, query, query, AgentExecutionMode.REACT);

        ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
        ChatSessionFileRepository chatSessionFileRepository = mock(ChatSessionFileRepository.class);
        AgentKnowledgeBaseRepository agentKbRepository = mock(AgentKnowledgeBaseRepository.class);
        KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        SessionFileSimilaritySearcher sessionSearcher = mock(SessionFileSimilaritySearcher.class);
        KnowledgeBaseSimilaritySearcher kbSearcher = mock(KnowledgeBaseSimilaritySearcher.class);
        KnowledgeDocumentSignalService signalService = mock(KnowledgeDocumentSignalService.class);
        RetrievalReranker reranker = mock(RetrievalReranker.class);
        when(chatSessionRepository.findById(sessionId)).thenReturn(ChatSessionDTO.builder()
                .id(sessionId).agentId("agent-1").build());
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-leave")))
                .thenReturn(List.of("kb-leave"));
        when(kbSearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("kb-leave"), query))
                .thenReturn(List.of(new MilvusSearchHit(
                        "chunk-1", "kb-leave", "doc-leave", 0, "Leave Policy", "Apply",
                        "Submit the leave form.", "context", "Submit the leave form.", 0.9d)));
        when(signalService.attachSignals(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reranker.rerank(eq(query), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(null);
        SearchScopeResolver resolver = new SearchScopeResolver(
                chatSessionRepository, chatSessionFileRepository, agentKbRepository,
                knowledgeBaseRepository, sessionSearcher, kbSearcher, signalService,
                reranker, 3, 60, meterProvider);
        RagService ragService = new RagService(mock(OllamaEmbeddingClient.class), resolver);
        CurrentTurnCitationHolder citationHolder = new CurrentTurnCitationHolder();
        SessionFileTools tools = new SessionFileTools(
                ragService, new RetrievalHitFormatter(TestPromptLoader.create()), citationHolder);
        ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(tools).build().getToolCallbacks()[0];

        AgentMessageBridge bridge = mock(AgentMessageBridge.class);
        LLMService llmService = mock(LLMService.class);
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        memory.add(sessionId, new UserMessage(query));
        var options = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false).build();
        AgentThinkingEngine thinking = new AgentThinkingEngine(
                TestPromptLoader.create(), llmService, options, List.of(callback), "", "",
                turnId, bridge, 4, contract);
        AgentToolExecutionEngine execution = new AgentToolExecutionEngine(
                List.of(callback), options, turnId, bridge);
        CurrentChatSessionHolder.set(sessionId);
        CurrentTurnHolder.set(turnId);
        CurrentIntentResolutionHolder.set(resolution);
        CurrentTurnExecutionContractHolder.set(contract);
        CurrentTurnKnowledgeHitHolder.reset();

        var decision = thinking.think(memory, sessionId);
        boolean terminated = execution.execute(memory, sessionId, decision);

        assertThat(terminated).isFalse();
        assertThat(decision.hasToolCalls()).isTrue();
        AssistantMessage assistant = decision.getResult().getOutput();
        assertThat(assistant.getToolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("SessionFileSearchTool");
            assertThat(call.arguments()).contains("\"routeKey\":\"q0\"");
        });
        assertThat(memory.get(sessionId).get(memory.get(sessionId).size() - 1))
                .isInstanceOfSatisfying(ToolResponseMessage.class, response ->
                        assertThat(response.getResponses()).singleElement()
                                .satisfies(toolResponse -> assertThat(toolResponse.responseData())
                                        .contains("Submit the leave form.")
                                        .contains("[1] Source: Leave Policy")));
        verify(kbSearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("kb-leave"), query);
        verify(sessionSearcher, never()).searchCandidateHitsBySessionFileIds(anyList(), eq(query));
        assertThat(citationHolder.peekRetrievalMetadata(sessionId, turnId).retrievalOutcomeDetail())
                .isEqualTo(RetrievalExecutionOutcome.HIT);
        assertThat(citationHolder.peek(sessionId, turnId)).hasSize(1);
    }

    @Test
    void compatibleMultiIntentExecutesEachRouteWithoutScopeCrossing() {
        String sessionId = "session-2";
        String turnId = "turn-2";
        String query = "compare policies";
        IntentResolution routeA = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("a").name("A").build()),
                List.of("kb-a"), ScopePolicy.STRICT, List.of(), null);
        IntentResolution routeB = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("b").name("B").build()),
                List.of("kb-b"), ScopePolicy.FALLBACK_ALLOWED, List.of(), null);
        TurnExecutionContract contract = ContractTestSupport.contractBuilder().buildForRoutes(
                List.of(routeA, routeB), query, query, AgentExecutionMode.REACT,
                multiUnderstanding());

        ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
        ChatSessionFileRepository chatSessionFileRepository = mock(ChatSessionFileRepository.class);
        AgentKnowledgeBaseRepository agentKbRepository = mock(AgentKnowledgeBaseRepository.class);
        KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        SessionFileSimilaritySearcher sessionSearcher = mock(SessionFileSimilaritySearcher.class);
        KnowledgeBaseSimilaritySearcher kbSearcher = mock(KnowledgeBaseSimilaritySearcher.class);
        KnowledgeDocumentSignalService signalService = mock(KnowledgeDocumentSignalService.class);
        RetrievalReranker reranker = mock(RetrievalReranker.class);
        when(chatSessionRepository.findById(sessionId)).thenReturn(ChatSessionDTO.builder()
                .id(sessionId).agentId("agent-2").build());
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-a"))).thenReturn(List.of("kb-a"));
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-b"))).thenReturn(List.of("kb-b"));
        when(agentKbRepository.findKnowledgeBaseIdsByAgentId("agent-2"))
                .thenReturn(List.of("kb-default"));
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-default")))
                .thenReturn(List.of("kb-default"));
        when(kbSearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("kb-a"), query))
                .thenReturn(List.of(hit("a-hit", "kb-a", "doc-a", "Policy A", "A evidence")));
        when(kbSearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("kb-b"), query))
                .thenReturn(List.of());
        when(kbSearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("kb-default"), query))
                .thenReturn(List.of(hit("b-hit", "kb-default", "doc-b", "Policy B", "B evidence")));
        when(signalService.attachSignals(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reranker.rerank(eq(query), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(null);
        SearchScopeResolver resolver = new SearchScopeResolver(
                chatSessionRepository, chatSessionFileRepository, agentKbRepository,
                knowledgeBaseRepository, sessionSearcher, kbSearcher, signalService,
                reranker, 3, 60, meterProvider);
        CurrentTurnCitationHolder citationHolder = new CurrentTurnCitationHolder();
        SessionFileTools tools = new SessionFileTools(
                new RagService(mock(OllamaEmbeddingClient.class), resolver),
                new RetrievalHitFormatter(TestPromptLoader.create()), citationHolder);
        ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(tools).build().getToolCallbacks()[0];
        AgentMessageBridge bridge = mock(AgentMessageBridge.class);
        var options = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false).build();
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        memory.add(sessionId, new UserMessage(query));
        AgentThinkingEngine thinking = new AgentThinkingEngine(
                TestPromptLoader.create(), mock(LLMService.class), options, List.of(callback), "", "",
                turnId, bridge, 4, contract);
        AgentToolExecutionEngine execution = new AgentToolExecutionEngine(
                List.of(callback), options, turnId, bridge);
        CurrentChatSessionHolder.set(sessionId);
        CurrentTurnHolder.set(turnId);
        CurrentIntentResolutionHolder.set(routeA);
        CurrentTurnExecutionContractHolder.set(contract);
        CurrentTurnKnowledgeHitHolder.reset();

        var decision = thinking.think(memory, sessionId);
        execution.execute(memory, sessionId, decision);

        assertThat(decision.getResult().getOutput().getToolCalls()).hasSize(2);
        verify(kbSearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("kb-a"), query);
        verify(kbSearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("kb-b"), query);
        verify(kbSearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("kb-default"), query);
        assertThat(citationHolder.peek(sessionId, turnId)).hasSize(2);
        assertThat(citationHolder.peekRetrievalMetadata(sessionId, turnId)).satisfies(metadata -> {
            assertThat(metadata.retrievalOutcomeDetail()).isEqualTo(RetrievalExecutionOutcome.FALLBACK_HIT);
            assertThat(metadata.policyFallbackApplied()).isTrue();
            assertThat(metadata.actualSources()).containsExactly(
                    com.yulong.chatagent.agent.runtime.contract.RetrievalSource.INTENT_KB,
                    com.yulong.chatagent.agent.runtime.contract.RetrievalSource.AGENT_DEFAULT_KB);
        });
    }

    private com.yulong.chatagent.intent.application.IntentUnderstandingResult multiUnderstanding() {
        List<com.yulong.chatagent.intent.application.IntentCandidateEvidence> evidence = List.of(
                new com.yulong.chatagent.intent.application.IntentCandidateEvidence(
                        "a", "A", 1.0d, 0.0d, 1, List.of("test")),
                new com.yulong.chatagent.intent.application.IntentCandidateEvidence(
                        "b", "B", 0.9d, 0.0d, 2, List.of("test")));
        var decision = new com.yulong.chatagent.intent.application.IntentDecision(
                com.yulong.chatagent.intent.application.IntentRouteOutcome.MULTI_INTENT,
                "a", List.of("b"), evidence, List.of(),
                com.yulong.chatagent.intent.application.IntentDecisionSource.CLASSIFIER,
                0.9d,
                com.yulong.chatagent.intent.application.ConfidenceStatus.CALIBRATED,
                "v1", List.of("test"));
        return new com.yulong.chatagent.intent.application.IntentUnderstandingResult(
                decision,
                com.yulong.chatagent.agent.runtime.contract.SourceNeed.KB,
                com.yulong.chatagent.agent.runtime.contract.TimeSensitivity.STATIC,
                com.yulong.chatagent.agent.runtime.contract.ActionRisk.READ_ONLY,
                List.of(com.yulong.chatagent.agent.runtime.contract.IntentLabel.MULTI_INTENT),
                false, false);
    }

    private MilvusSearchHit hit(String chunkId,
                                String sourceId,
                                String documentId,
                                String documentName,
                                String content) {
        return new MilvusSearchHit(
                chunkId, sourceId, documentId, 0, documentName, "Section",
                content, "context", content, 0.9d);
    }
}
