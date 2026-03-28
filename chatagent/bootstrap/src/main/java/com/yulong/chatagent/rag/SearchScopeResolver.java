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
import com.yulong.chatagent.rag.retrieve.KnowledgeBaseSimilaritySearcher;
import com.yulong.chatagent.rag.retrieve.RetrievalReranker;
import com.yulong.chatagent.rag.retrieve.SessionFileSimilaritySearcher;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves which retrieval scopes are relevant for one chat session and merges the results.
 */
@Component
public class SearchScopeResolver {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionFileRepository chatSessionFileRepository;
    private final AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SessionFileSimilaritySearcher sessionFileSimilaritySearcher;
    private final KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher;
    private final RetrievalReranker retrievalReranker;
    private final int topK;
    private final int rrfK;

    public SearchScopeResolver(ChatSessionRepository chatSessionRepository,
                               ChatSessionFileRepository chatSessionFileRepository,
                               AgentKnowledgeBaseRepository agentKnowledgeBaseRepository,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               SessionFileSimilaritySearcher sessionFileSimilaritySearcher,
                               KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher,
                               RetrievalReranker retrievalReranker,
                               @Value("${rag.retrieval.top-k:3}") int topK,
                               @Value("${rag.retrieval.rrf-k:60}") int rrfK) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatSessionFileRepository = chatSessionFileRepository;
        this.agentKnowledgeBaseRepository = agentKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.sessionFileSimilaritySearcher = sessionFileSimilaritySearcher;
        this.knowledgeBaseSimilaritySearcher = knowledgeBaseSimilaritySearcher;
        this.retrievalReranker = retrievalReranker;
        this.topK = topK;
        this.rrfK = rrfK;
    }

    public List<RetrievalHit> searchBySession(String chatSessionId, String queryText) {
        return searchBySession(chatSessionId, queryText, null);
    }

    public List<RetrievalHit> searchBySession(String chatSessionId,
                                              String queryText,
                                              IntentResolution intentResolution) {
        if (!StringUtils.hasText(chatSessionId) || !StringUtils.hasText(queryText)) {
            return List.of();
        }

        ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
        if (chatSession == null) {
            return List.of();
        }

        List<String> sessionFileIds = resolveSessionFileIds(chatSessionId);
        List<MilvusSearchHit> sessionHits = sessionFileSimilaritySearcher.searchCandidateHitsBySessionFileIds(sessionFileIds, queryText);
        List<MilvusSearchHit> knowledgeBaseHits = resolveKnowledgeHits(chatSession, queryText, intentResolution);

        return rerankAndLimit(queryText, fuseHits(knowledgeBaseHits, sessionHits));
    }

    private List<String> resolveSessionFileIds(String chatSessionId) {
        List<String> ids = new ArrayList<>();
        for (ChatSessionFileDTO file : chatSessionFileRepository.findBySessionId(chatSessionId)) {
            if (StringUtils.hasText(file.getId())) {
                ids.add(file.getId());
            }
        }
        return ids;
    }

    private List<String> resolveKnowledgeBaseIds(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        List<String> candidateIds = agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId(agentId);
        return knowledgeBaseRepository.filterActiveIds(candidateIds);
    }

    private List<MilvusSearchHit> resolveKnowledgeHits(ChatSessionDTO chatSession,
                                                       String queryText,
                                                       IntentResolution intentResolution) {
        List<String> agentKnowledgeBaseIds = resolveKnowledgeBaseIds(chatSession.getAgentId());
        if (intentResolution == null) {
            return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(agentKnowledgeBaseIds, queryText);
        }
        if (intentResolution.kind() != IntentKind.KB) {
            return List.of();
        }

        List<String> scopedKnowledgeBaseIds = knowledgeBaseRepository.filterActiveIds(intentResolution.scopedKbIds());
        List<MilvusSearchHit> scopedHits = knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(scopedKnowledgeBaseIds, queryText);
        if (!scopedHits.isEmpty()) {
            return scopedHits;
        }
        if (intentResolution.scopePolicy() != ScopePolicy.FALLBACK_ALLOWED) {
            return List.of();
        }

        List<String> fallbackKnowledgeBaseIds = agentKnowledgeBaseIds.stream()
                .filter(id -> !scopedKnowledgeBaseIds.contains(id))
                .toList();
        return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(fallbackKnowledgeBaseIds, queryText);
    }

    private List<ScopedHit> fuseHits(List<MilvusSearchHit> knowledgeBaseHits, List<MilvusSearchHit> sessionHits) {
        Map<String, RankedHit> fused = new LinkedHashMap<>();
        addHitsByRrf(fused, RagSourceType.KNOWLEDGE_BASE, knowledgeBaseHits);
        addHitsByRrf(fused, RagSourceType.SESSION_FILE, sessionHits);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RankedHit::score).reversed())
                .map(ranked -> ranked.scopedHit().withFusedScore(ranked.score()))
                .toList();
    }

    private List<RetrievalHit> rerankAndLimit(String queryText, List<ScopedHit> fusedHits) {
        if (fusedHits.isEmpty()) {
            return List.of();
        }

        List<MilvusSearchHit> rerankCandidates = fusedHits.stream()
                .map(this::toRerankHit)
                .toList();
        List<MilvusSearchHit> reranked = retrievalReranker.rerank(queryText, rerankCandidates);

        Map<String, ScopedHit> bySyntheticChunkId = new LinkedHashMap<>();
        for (ScopedHit hit : fusedHits) {
            bySyntheticChunkId.put(hit.syntheticChunkId(), hit);
        }

        List<RetrievalHit> ordered = new ArrayList<>();
        for (MilvusSearchHit rerankedHit : reranked) {
            ScopedHit scopedHit = bySyntheticChunkId.get(rerankedHit.chunkId());
            if (scopedHit != null) {
                ordered.add(toRetrievalHit(scopedHit, rerankedHit));
            }
            if (ordered.size() >= topK) {
                break;
            }
        }
        return ordered;
    }

    private void addHitsByRrf(Map<String, RankedHit> fused, RagSourceType sourceType, List<MilvusSearchHit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            MilvusSearchHit hit = hits.get(i);
            String key = hitKey(sourceType, hit);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            ScopedHit scopedHit = new ScopedHit(sourceType, hit, key, 0.0d);
            double rrfScore = 1.0d / (rrfK + i + 1);
            fused.compute(key, (ignored, existing) -> existing == null
                    ? new RankedHit(scopedHit, rrfScore)
                    : new RankedHit(existing.scopedHit(), existing.score() + rrfScore));
        }
    }

    private String hitKey(RagSourceType sourceType, MilvusSearchHit hit) {
        if (hit == null || sourceType == null || !StringUtils.hasText(hit.documentId())) {
            return null;
        }
        return sourceType.name()
                + "::" + defaultString(hit.sourceId())
                + "::" + hit.documentId()
                + "::" + hit.chunkIndex()
                + "::" + defaultString(hit.sectionPath());
    }

    private MilvusSearchHit toRerankHit(ScopedHit scopedHit) {
        MilvusSearchHit hit = scopedHit.hit();
        return new MilvusSearchHit(
                scopedHit.syntheticChunkId(),
                hit.sourceId(),
                hit.documentId(),
                hit.chunkIndex(),
                hit.documentName(),
                hit.sectionPath(),
                hit.content(),
                hit.contextText(),
                hit.retrievalText(),
                scopedHit.fusedScore()
        );
    }

    private RetrievalHit toRetrievalHit(ScopedHit scopedHit, MilvusSearchHit rerankedHit) {
        MilvusSearchHit hit = scopedHit.hit();
        Integer chunkIndex = hit.chunkIndex() >= 0 ? hit.chunkIndex() : null;
        String sectionPath = hit.sectionPath();
        if (scopedHit.sourceType() == RagSourceType.SESSION_FILE && !StringUtils.hasText(sectionPath) && chunkIndex != null) {
            sectionPath = "chunk[" + chunkIndex + "]";
        }
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText();
        Double finalScore = rerankedHit.score();
        String scoreType = normalizeScoreType(rerankedHit.scoreType(), finalScore);

        return new RetrievalHit(
                scopedHit.sourceType(),
                hit.sourceId(),
                hit.documentId(),
                hit.documentName(),
                chunkIndex,
                sectionPath,
                content,
                hit.contextText(),
                finalScore,
                scoreType,
                "fallback".equals(scoreType)
        );
    }

    private String normalizeScoreType(String scoreType, Double score) {
        if (StringUtils.hasText(scoreType)) {
            return scoreType;
        }
        return score == null ? "fallback" : "retrieval";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record RankedHit(ScopedHit scopedHit, double score) {
    }

    private record ScopedHit(RagSourceType sourceType,
                             MilvusSearchHit hit,
                             String syntheticChunkId,
                             double fusedScore) {

        private ScopedHit withFusedScore(double fusedScore) {
            return new ScopedHit(sourceType, hit, syntheticChunkId, fusedScore);
        }
    }
}
