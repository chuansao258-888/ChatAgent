package com.yulong.chatagent.rag;

import com.yulong.chatagent.admin.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.knowledge.port.KnowledgeBaseRepository;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.RetrievalReranker;
import com.yulong.chatagent.rag.retrieve.SessionFileSimilaritySearcher;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchScopeResolverTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatSessionFileRepository chatSessionFileRepository;

    @Mock
    private AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private SessionFileSimilaritySearcher sessionFileSimilaritySearcher;

    @Mock
    private KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher;

    @Mock
    private KnowledgeDocumentSignalService knowledgeDocumentSignalService;

    @Mock
    private RetrievalReranker retrievalReranker;

    private SearchScopeResolver searchScopeResolver;

    @BeforeEach
    void setUp() {
        searchScopeResolver = new SearchScopeResolver(
                chatSessionRepository,
                chatSessionFileRepository,
                agentKnowledgeBaseRepository,
                knowledgeBaseRepository,
                sessionFileSimilaritySearcher,
                knowledgeBaseSimilaritySearcher,
                knowledgeDocumentSignalService,
                retrievalReranker,
                3,
                60
        );
        lenient().when(knowledgeDocumentSignalService.attachSignals(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldMergeKnowledgeBaseAndSessionHitsUsingUnifiedRerank() {
        when(chatSessionRepository.findById("session-1")).thenReturn(ChatSessionDTO.builder()
                .id("session-1")
                .agentId("assistant-1")
                .build());
        when(chatSessionFileRepository.findBySessionId("session-1")).thenReturn(List.of(
                ChatSessionFileDTO.builder().id("file-1").build()
        ));
        when(agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId("assistant-1")).thenReturn(List.of("kb-1", "kb-2"));
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-1", "kb-2"))).thenReturn(List.of("kb-1"));
        when(knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("kb-1"), "leave policy")).thenReturn(List.of(
                candidateHit("kb-chunk-1", "kb-1", "doc-1", "HR Handbook", 1, "Benefits / Leave", "Carry over policy", "Carry over up to 5 days", 0.91f),
                candidateHit("kb-chunk-2", "kb-1", "doc-2", "Operations FAQ", 2, "Remote Work", "Remote work FAQ", "Remote work policy", 0.72f)
        ));
        when(sessionFileSimilaritySearcher.searchCandidateHitsBySessionFileIds(List.of("file-1"), "leave policy")).thenReturn(List.of(
                candidateHit("file-chunk-1", "file-1", "file-1", "upload.md", 0, null, "leave request form", "leave request form", 0.88f)
        ));
        when(knowledgeDocumentSignalService.attachSignals(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MilvusSearchHit> candidates = (List<MilvusSearchHit>) invocation.getArgument(0);
            assertThat(candidates).extracting(MilvusSearchHit::documentId)
                    .containsExactly("doc-1", "doc-2");
            return candidates.stream()
                    .map(hit -> "doc-1".equals(hit.documentId())
                            ? hit.withDocumentKeywords(List.of("leave policy"))
                            .withDocumentQuestions(List.of("How many leave days can be carried over?"))
                            : hit)
                    .toList();
        });
        when(retrievalReranker.rerank(eq("leave policy"), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MilvusSearchHit> candidates = (List<MilvusSearchHit>) invocation.getArgument(1);
            assertThat(candidates.stream()
                    .filter(hit -> "doc-1".equals(hit.documentId()))
                    .findFirst()
                    .orElseThrow()
                    .documentKeywords()).containsExactly("leave policy");
            return List.of(
                    candidates.get(1).withScore(0.95d).withScoreType("reranker"),
                    candidates.get(0).withScore(0.85d).withScoreType("reranker"),
                    candidates.get(2).withScore(0.71d).withScoreType("reranker")
            );
        });

        List<RetrievalHit> hits = searchScopeResolver.searchBySession("session-1", "leave policy");

        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).sourceType()).isEqualTo(RagSourceType.SESSION_FILE);
        assertThat(hits.get(0).documentId()).isEqualTo("file-1");
        assertThat(hits.get(0).score()).isEqualTo(0.95d);
        assertThat(hits.get(0).scoreType()).isEqualTo("reranker");
        assertThat(hits.get(0).isFallback()).isFalse();
        assertThat(hits.get(1).score()).isEqualTo(0.85d);
        assertThat(hits.get(2).score()).isEqualTo(0.71d);
        assertThat(hits).extracting(RetrievalHit::documentId)
                .containsExactly("file-1", "doc-1", "doc-2");
        verify(sessionFileSimilaritySearcher).searchCandidateHitsBySessionFileIds(List.of("file-1"), "leave policy");
        verify(knowledgeBaseSimilaritySearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("kb-1"), "leave policy");
        verify(knowledgeDocumentSignalService).attachSignals(anyList());
        verify(retrievalReranker).rerank(eq("leave policy"), anyList());
    }

    @Test
    void shouldReturnEmptyWhenSessionDoesNotExist() {
        when(chatSessionRepository.findById("missing")).thenReturn(null);

        assertThat(searchScopeResolver.searchBySession("missing", "any")).isEmpty();
    }

    @Test
    void shouldFallbackToAssistantKnowledgeBasesOnlyWhenPolicyAllows() {
        when(chatSessionRepository.findById("session-1")).thenReturn(ChatSessionDTO.builder()
                .id("session-1")
                .agentId("assistant-1")
                .build());
        when(chatSessionFileRepository.findBySessionId("session-1")).thenReturn(List.of());
        when(agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId("assistant-1")).thenReturn(List.of("kb-1", "kb-2"));
        when(knowledgeBaseRepository.filterActiveIds(List.of("scoped-kb"))).thenReturn(List.of("scoped-kb"));
        when(knowledgeBaseRepository.filterActiveIds(List.of("kb-1", "kb-2"))).thenReturn(List.of("kb-1", "kb-2"));
        when(knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("scoped-kb"), "travel reimbursement"))
                .thenReturn(List.of());
        when(knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(List.of("kb-1", "kb-2"), "travel reimbursement"))
                .thenReturn(List.of(candidateHit("kb-chunk-1", "kb-1", "doc-1", "Finance Handbook", 0, "报销", "报销政策", "报销政策", 0.91f)));
        when(retrievalReranker.rerank(eq("travel reimbursement"), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        List<RetrievalHit> hits = searchScopeResolver.searchBySession(
                "session-1",
                "travel reimbursement",
                new IntentResolution(IntentKind.KB, List.of(), List.of("scoped-kb"), ScopePolicy.FALLBACK_ALLOWED, List.of(), null)
        );

        assertThat(hits).hasSize(1);
        verify(knowledgeBaseSimilaritySearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("scoped-kb"), "travel reimbursement");
        verify(knowledgeBaseSimilaritySearcher).searchCandidateHitsByKnowledgeBaseIds(List.of("kb-1", "kb-2"), "travel reimbursement");
    }

    @Test
    void shouldPropagateFallbackMetadataWhenRerankerReturnsNullScores() {
        when(chatSessionRepository.findById("session-1")).thenReturn(ChatSessionDTO.builder()
                .id("session-1")
                .agentId("assistant-1")
                .build());
        when(chatSessionFileRepository.findBySessionId("session-1")).thenReturn(List.of(
                ChatSessionFileDTO.builder().id("file-1").build()
        ));
        when(agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId("assistant-1")).thenReturn(List.of());
        when(knowledgeBaseRepository.filterActiveIds(List.of())).thenReturn(List.of());
        when(sessionFileSimilaritySearcher.searchCandidateHitsBySessionFileIds(List.of("file-1"), "leave policy")).thenReturn(List.of(
                candidateHit("file-chunk-1", "file-1", "file-1", "upload.md", 0, null, "leave request form", "leave request form", 0.88f),
                candidateHit("file-chunk-2", "file-1", "file-2", "upload-2.md", 1, null, "carry over rules", "carry over rules", 0.74f)
        ));
        when(knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(List.of(), "leave policy")).thenReturn(List.of());
        when(retrievalReranker.rerank(eq("leave policy"), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MilvusSearchHit> candidates = (List<MilvusSearchHit>) invocation.getArgument(1);
            return candidates.stream()
                    .map(hit -> hit.withScore(null).withScoreType("fallback"))
                    .toList();
        });

        List<RetrievalHit> hits = searchScopeResolver.searchBySession("session-1", "leave policy");

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.score()).isNull();
            assertThat(hit.scoreType()).isEqualTo("fallback");
            assertThat(hit.isFallback()).isTrue();
        });
        verify(knowledgeDocumentSignalService, never()).attachSignals(anyList());
    }

    @Test
    void shouldPropagateFilteredMetadataWithoutMarkingFallback() {
        when(chatSessionRepository.findById("session-1")).thenReturn(ChatSessionDTO.builder()
                .id("session-1")
                .agentId("assistant-1")
                .build());
        when(chatSessionFileRepository.findBySessionId("session-1")).thenReturn(List.of(
                ChatSessionFileDTO.builder().id("file-1").build()
        ));
        when(agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId("assistant-1")).thenReturn(List.of());
        when(knowledgeBaseRepository.filterActiveIds(List.of())).thenReturn(List.of());
        when(sessionFileSimilaritySearcher.searchCandidateHitsBySessionFileIds(List.of("file-1"), "leave policy")).thenReturn(List.of(
                candidateHit("file-chunk-1", "file-1", "file-1", "upload.md", 0, null, "leave request form", "leave request form", 0.88f),
                candidateHit("file-chunk-2", "file-1", "file-2", "upload-2.md", 1, null, "carry over rules", "carry over rules", 0.74f)
        ));
        when(knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(List.of(), "leave policy")).thenReturn(List.of());
        when(retrievalReranker.rerank(eq("leave policy"), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MilvusSearchHit> candidates = (List<MilvusSearchHit>) invocation.getArgument(1);
            return candidates.stream()
                    .map(hit -> hit.withScore(null).withScoreType("filtered"))
                    .toList();
        });

        List<RetrievalHit> hits = searchScopeResolver.searchBySession("session-1", "leave policy");

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.score()).isNull();
            assertThat(hit.scoreType()).isEqualTo("filtered");
            assertThat(hit.isFallback()).isFalse();
        });
        verify(knowledgeDocumentSignalService, never()).attachSignals(anyList());
    }

    private MilvusSearchHit candidateHit(String chunkId,
                                         String sourceId,
                                         String documentId,
                                         String documentName,
                                         int chunkIndex,
                                         String sectionPath,
                                         String content,
                                         String retrievalText,
                                         double score) {
        return new MilvusSearchHit(
                chunkId,
                sourceId,
                documentId,
                chunkIndex,
                documentName,
                sectionPath,
                content,
                "context",
                retrievalText,
                score
        );
    }
}
