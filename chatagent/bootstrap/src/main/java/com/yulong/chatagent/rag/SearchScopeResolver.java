package com.yulong.chatagent.rag;

import com.yulong.chatagent.agent.port.AgentKnowledgeBaseRepository;
import com.yulong.chatagent.agent.runtime.contract.RetrievalFallbackPolicy;
import com.yulong.chatagent.agent.runtime.contract.RetrievalMode;
import com.yulong.chatagent.agent.runtime.contract.RetrievalPlan;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
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
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Resolves which retrieval scopes are relevant for one chat session and merges the results.
 * <p>
 * 它是 knowledgeQuery 后面的核心检索编排器，负责回答三个问题：
 * <ol>
 *     <li>本轮应该查哪些来源：会话文件、Agent 绑定知识库、还是意图路由命中的 scoped KB。</li>
 *     <li>不同来源返回的候选 chunk 如何融合去重。</li>
 *     <li>融合后的候选如何 rerank 并截断成最终给模型的 topK 证据。</li>
 * </ol>
 */
@Slf4j
@Component
public class SearchScopeResolver {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionFileRepository chatSessionFileRepository;
    private final AgentKnowledgeBaseRepository agentKnowledgeBaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SessionFileSimilaritySearcher sessionFileSimilaritySearcher;
    private final KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher;
    private final KnowledgeDocumentSignalService knowledgeDocumentSignalService;
    private final RetrievalReranker retrievalReranker;
    private final int topK;
    private final int rrfK;
    private final MeterRegistry meterRegistry;

    public SearchScopeResolver(ChatSessionRepository chatSessionRepository,
                               ChatSessionFileRepository chatSessionFileRepository,
                               AgentKnowledgeBaseRepository agentKnowledgeBaseRepository,
                               KnowledgeBaseRepository knowledgeBaseRepository,
                               SessionFileSimilaritySearcher sessionFileSimilaritySearcher,
                               KnowledgeBaseSimilaritySearcher knowledgeBaseSimilaritySearcher,
                               KnowledgeDocumentSignalService knowledgeDocumentSignalService,
                               RetrievalReranker retrievalReranker,
                               @Value("${rag.retrieval.top-k:3}") int topK,
                               @Value("${rag.retrieval.rrf-k:60}") int rrfK,
                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatSessionFileRepository = chatSessionFileRepository;
        this.agentKnowledgeBaseRepository = agentKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.sessionFileSimilaritySearcher = sessionFileSimilaritySearcher;
        this.knowledgeBaseSimilaritySearcher = knowledgeBaseSimilaritySearcher;
        this.knowledgeDocumentSignalService = knowledgeDocumentSignalService;
        this.retrievalReranker = retrievalReranker;
        this.topK = topK;
        this.rrfK = rrfK;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public List<RetrievalHit> searchBySession(String chatSessionId, String queryText) {
        // 没有意图上下文的兼容入口：查当前会话文件 + Agent 默认绑定知识库。
        return searchBySession(chatSessionId, queryText, null, null, null);
    }

    public List<RetrievalHit> searchBySession(String chatSessionId,
                                              String queryText,
                                              IntentResolution intentResolution) {
        return searchBySession(chatSessionId, queryText, intentResolution, null, null);
    }

    public List<RetrievalHit> searchBySession(String chatSessionId,
                                              String queryText,
                                              IntentResolution intentResolution,
                                              RetrievalPlan retrievalPlan,
                                              RetrievalSource querySource) {
        if (!StringUtils.hasText(chatSessionId) || !StringUtils.hasText(queryText)) {
            // sessionId 或 query 为空时无法构造可靠检索范围，直接返回空结果。
            return List.of();
        }

        long startMs = System.currentTimeMillis();
        try {
            ChatSessionDTO chatSession = chatSessionRepository.findById(chatSessionId);
            if (chatSession == null) {
                // 会话不存在说明上游上下文已经失效；这里不抛异常，避免 RAG miss 放大成整轮失败。
                return List.of();
            }
            if (retrievalPlan != null && retrievalPlan.mode() == RetrievalMode.DISABLED) {
                return List.of();
            }

            // 先分别拿两路候选：当前会话附件、Agent/意图允许的知识库。
            // 两路来源的相似度分数未必完全可比，所以后面先用 RRF 做融合，再统一 rerank。
            RetrievalSource effectiveSource = effectiveSource(retrievalPlan, querySource);
            List<MilvusSearchHit> sessionHits = shouldSearchSessionFiles(retrievalPlan, effectiveSource)
                    ? sessionFileSimilaritySearcher.searchCandidateHitsBySessionFileIds(
                            resolveSessionFileIds(chatSessionId), queryText)
                    : List.of();
            List<MilvusSearchHit> knowledgeBaseHits = retrievalPlan == null
                    ? resolveKnowledgeHits(chatSession, queryText, intentResolution)
                    : resolveKnowledgeHits(chatSession, queryText, retrievalPlan, effectiveSource);

            return rerankAndLimit(queryText, fuseHits(knowledgeBaseHits, sessionHits));
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            recordTimer("chatagent.rag.retrieval.latency", durationMs);
        }
    }

    private RetrievalSource effectiveSource(RetrievalPlan plan, RetrievalSource querySource) {
        if (plan == null) {
            return null;
        }
        if (querySource == plan.source()) {
            return querySource;
        }
        if (plan.source() == RetrievalSource.MIXED_SESSION_AND_KB && isMixedPlanSource(querySource)) {
            return querySource;
        }
        return plan.source();
    }

    private boolean isMixedPlanSource(RetrievalSource source) {
        return source == RetrievalSource.SESSION_FILES
                || source == RetrievalSource.INTENT_KB
                || source == RetrievalSource.AGENT_DEFAULT_KB
                || source == RetrievalSource.MIXED_SESSION_AND_KB;
    }

    private boolean shouldSearchSessionFiles(RetrievalPlan plan, RetrievalSource source) {
        if (plan == null) {
            return true;
        }
        return source == RetrievalSource.SESSION_FILES
                || source == RetrievalSource.MIXED_SESSION_AND_KB
                || plan.fallbackPolicy() == RetrievalFallbackPolicy.SESSION_FILES_ONLY;
    }

    private List<String> resolveSessionFileIds(String chatSessionId) {
        // 会话文件范围来自 chat_session_file 关系表，只允许检索当前 session 已绑定的文件。
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
        // Agent 绑定的知识库是默认 KB 检索池；再经过 active 过滤，避免查到禁用/删除的知识库。
        List<String> candidateIds = agentKnowledgeBaseRepository.findKnowledgeBaseIdsByAgentId(agentId);
        return knowledgeBaseRepository.filterActiveIds(candidateIds);
    }

    private List<MilvusSearchHit> resolveKnowledgeHits(ChatSessionDTO chatSession,
                                                       String queryText,
                                                       IntentResolution intentResolution) {
        List<String> agentKnowledgeBaseIds = resolveKnowledgeBaseIds(chatSession.getAgentId());
        if (intentResolution == null) {
            // 没有意图路由时，按 Agent 的默认知识库池检索。
            return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(agentKnowledgeBaseIds, queryText);
        }
        if (intentResolution.kind() != IntentKind.KB) {
            // 非 KB 意图不查知识库，避免普通聊天/工具任务被无关知识库污染。
            // 会话附件仍然会在 searchBySession 主流程中单独检索。
            return List.of();
        }

        // KB 意图下优先查意图路由命中的 scoped KB。
        // scopedKbIds 也要做 active 过滤，避免路由结果引用到已禁用知识库。
        List<String> scopedKnowledgeBaseIds = knowledgeBaseRepository.filterActiveIds(intentResolution.scopedKbIds());
        List<MilvusSearchHit> scopedHits = knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(scopedKnowledgeBaseIds, queryText);
        if (!scopedHits.isEmpty()) {
            return scopedHits;
        }
        if (intentResolution.scopePolicy() != ScopePolicy.FALLBACK_ALLOWED) {
            // 如果策略不允许 fallback，scoped KB 没结果就返回空，严格遵守意图边界。
            return List.of();
        }

        // fallback 只查 Agent 默认池里“非 scoped”的知识库，避免重复查同一批 KB。
        List<String> fallbackKnowledgeBaseIds = agentKnowledgeBaseIds.stream()
                .filter(id -> !scopedKnowledgeBaseIds.contains(id))
                .toList();
        return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(fallbackKnowledgeBaseIds, queryText);
    }

    private List<MilvusSearchHit> resolveKnowledgeHits(ChatSessionDTO chatSession,
                                                       String queryText,
                                                       RetrievalPlan plan,
                                                       RetrievalSource source) {
        if (source == null
                || source == RetrievalSource.NONE
                || source == RetrievalSource.WEB_SEARCH
                || source == RetrievalSource.SESSION_FILES) {
            return List.of();
        }
        if (source == RetrievalSource.AGENT_DEFAULT_KB) {
            return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(
                    resolveKnowledgeBaseIds(chatSession.getAgentId()), queryText);
        }

        List<String> scopedIds = knowledgeBaseRepository.filterActiveIds(plan.scopedKbIds());
        if (source == RetrievalSource.MIXED_SESSION_AND_KB && scopedIds.isEmpty()) {
            return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(
                    resolveKnowledgeBaseIds(chatSession.getAgentId()), queryText);
        }
        List<MilvusSearchHit> scopedHits =
                knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(scopedIds, queryText);
        if (!scopedHits.isEmpty() || plan.fallbackPolicy() != RetrievalFallbackPolicy.AGENT_DEFAULT_KB) {
            return scopedHits;
        }
        List<String> agentKnowledgeBaseIds = resolveKnowledgeBaseIds(chatSession.getAgentId());
        List<String> fallbackIds = agentKnowledgeBaseIds.stream()
                .filter(id -> !scopedIds.contains(id))
                .toList();
        return knowledgeBaseSimilaritySearcher.searchCandidateHitsByKnowledgeBaseIds(
                fallbackIds, queryText);
    }

    private List<ScopedHit> fuseHits(List<MilvusSearchHit> knowledgeBaseHits, List<MilvusSearchHit> sessionHits) {
        // RRF 融合：分别按来源内部排序位置给分，再把相同 chunk 的分数累加。
        // 这样可以把会话文件和知识库候选放到一个统一候选池里，而不直接比较原始向量分数。
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

        // reranker 接收的是 MilvusSearchHit 形态，所以先把带来源信息的 ScopedHit 转回 rerank candidate。
        // syntheticChunkId 会编码来源信息，避免不同来源里 document/chunk 相同导致回填时混淆。
        List<MilvusSearchHit> rerankCandidates = fusedHits.stream()
                .map(this::toRerankHit)
                .toList();
        // 知识库候选可能带文档关键词/候选问题等增强信号；rerank 前把这些信号补齐。
        rerankCandidates = attachKnowledgeSignalsForKnowledgeBaseHits(fusedHits, rerankCandidates);
        List<MilvusSearchHit> reranked = retrievalReranker.rerank(
                queryText,
                rerankCandidates
        );

        // reranker 返回的是候选排序结果；这里通过 syntheticChunkId 找回原来的来源类型和原始 hit。
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
                // topK 是最终给 LLM 的证据条数上限，不是向量库候选召回数量。
                break;
            }
        }
        return ordered;
    }

    private List<MilvusSearchHit> attachKnowledgeSignalsForKnowledgeBaseHits(List<ScopedHit> fusedHits,
                                                                              List<MilvusSearchHit> rerankCandidates) {
        List<MilvusSearchHit> knowledgeBaseCandidates = new ArrayList<>();
        for (int i = 0; i < fusedHits.size(); i++) {
            ScopedHit scopedHit = fusedHits.get(i);
            if (scopedHit.sourceType() == RagSourceType.KNOWLEDGE_BASE) {
                // 只给知识库来源补充文档级信号；会话文件没有对应的 KB 文档画像。
                knowledgeBaseCandidates.add(rerankCandidates.get(i));
            }
        }
        if (knowledgeBaseCandidates.isEmpty()) {
            return rerankCandidates;
        }

        List<MilvusSearchHit> enrichedKnowledgeHits = knowledgeDocumentSignalService.attachSignals(knowledgeBaseCandidates);
        if (enrichedKnowledgeHits.isEmpty()) {
            return rerankCandidates;
        }

        // attachSignals 可能只返回部分增强成功的候选，所以下面按 chunkId 做覆盖式合并。
        Map<String, MilvusSearchHit> enrichedByChunkId = new LinkedHashMap<>();
        for (MilvusSearchHit hit : enrichedKnowledgeHits) {
            if (StringUtils.hasText(hit.chunkId())) {
                enrichedByChunkId.put(hit.chunkId(), hit);
            }
        }

        List<MilvusSearchHit> mergedCandidates = new ArrayList<>(rerankCandidates.size());
        for (MilvusSearchHit candidate : rerankCandidates) {
            MilvusSearchHit enriched = enrichedByChunkId.get(candidate.chunkId());
            mergedCandidates.add(enriched == null ? candidate : enriched);
        }
        return mergedCandidates;
    }

    private void addHitsByRrf(Map<String, RankedHit> fused, RagSourceType sourceType, List<MilvusSearchHit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            MilvusSearchHit hit = hits.get(i);
            String key = hitKey(sourceType, hit);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            ScopedHit scopedHit = new ScopedHit(sourceType, hit, key, 0.0d);
            // RRF 分数只依赖排名位置：越靠前分数越高；rrfK 越大，不同名次之间的差距越平滑。
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
        // key 中包含 sourceType/sourceId/documentId/chunkIndex/sectionPath，尽量把“同一来源同一 chunk”识别为同一个证据。
        // sourceType 必须参与 key，否则会话文件和知识库里同名文档的 chunk 可能被错误合并。
        return sourceType.name()
                + "::" + defaultString(hit.sourceId())
                + "::" + hit.documentId()
                + "::" + hit.chunkIndex()
                + "::" + defaultString(hit.sectionPath());
    }

    private MilvusSearchHit toRerankHit(ScopedHit scopedHit) {
        MilvusSearchHit hit = scopedHit.hit();
        // reranker 不关心业务来源类型，但回填需要稳定 ID，所以把 fused 阶段的 syntheticChunkId 放进 chunkId。
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
                hit.documentKeywords(),
                hit.documentQuestions(),
                scopedHit.fusedScore()
        );
    }

    private RetrievalHit toRetrievalHit(ScopedHit scopedHit, MilvusSearchHit rerankedHit) {
        MilvusSearchHit hit = scopedHit.hit();
        Integer chunkIndex = hit.chunkIndex() >= 0 ? hit.chunkIndex() : null;
        String sectionPath = hit.sectionPath();
        if (scopedHit.sourceType() == RagSourceType.SESSION_FILE && !StringUtils.hasText(sectionPath) && chunkIndex != null) {
            // 会话文件的解析结果不一定有章节路径；用 chunk[n] 给模型和引用面板一个可读位置。
            sectionPath = "chunk[" + chunkIndex + "]";
        }
        String content = StringUtils.hasText(hit.content()) ? hit.content() : hit.retrievalText();
        Double finalScore = rerankedHit.score();
        String scoreType = normalizeScoreType(rerankedHit.scoreType(), finalScore);

        // RetrievalHit 是 Agent/RAG 之间的稳定结构：保留来源、文档、chunk、内容、分数和 fallback 标记。
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
        // 没有 score 且没有 scoreType，说明 reranker/检索器退化到了兜底排序。
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

    private void recordTimer(String name, long durationMs, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.timer(name, tags).record(Math.max(durationMs, 0L), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to record retrieval timer: name={}, error={}", name, e.getMessage());
        }
    }
}
