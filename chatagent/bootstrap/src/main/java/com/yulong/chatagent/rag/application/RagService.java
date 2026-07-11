package com.yulong.chatagent.rag.application;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.agent.runtime.contract.RetrievalPlan;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.rag.SearchScopeResolver;
import com.yulong.chatagent.rag.embedding.OllamaEmbeddingClient;
import com.yulong.chatagent.rag.model.RetrievalExecutionResult;
import com.yulong.chatagent.rag.model.RetrievalHit;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default RAG service delegating embedding and retrieval to dedicated collaborators.
 * <p>
 * 这个类是应用层门面：Agent 工具不直接依赖向量库、reranker 或知识库仓储，
 * 而是通过 RagService 进入检索链路。真正的范围解析和结果融合在 SearchScopeResolver 中完成。
 */
@Service
public class RagService {

    private final OllamaEmbeddingClient embeddingClient;
    private final SearchScopeResolver searchScopeResolver;

    public RagService(OllamaEmbeddingClient embeddingClient,
                      SearchScopeResolver searchScopeResolver) {
        this.embeddingClient = embeddingClient;
        this.searchScopeResolver = searchScopeResolver;
    }

    /**
     * Produces an embedding vector for one text input.
     *
     * @param text source text
     * @return embedding vector
     */
    public float[] embed(String text) {
        // embedding 生成仍保留在 RagService 门面上，方便 ingestion/eval 等场景复用同一个 embedding client。
        return embeddingClient.embed(text);
    }

    /**
     * Performs similarity search within the files attached to one chat session.
     *
     * @param chatSessionId chat session identifier
     * @param query search query text
     * @return structured retrieval hits
     */
    public List<RetrievalHit> similaritySearchBySession(String chatSessionId, String query) {
        // 旧入口：没有 intentResolution 时，按当前会话默认附件 + Agent 绑定知识库检索。
        return searchScopeResolver.searchBySession(chatSessionId, query);
    }

    public List<RetrievalHit> similaritySearchBySession(String chatSessionId,
                                                        String query,
                                                        IntentResolution intentResolution) {
        // Agent runtime 入口：intentResolution 会把 KB 检索限定在本轮意图允许的知识库范围内。
        // SessionFileTools.knowledgeQuery 走这个方法。
        return searchScopeResolver.searchBySession(chatSessionId, query, intentResolution);
    }

    public List<RetrievalHit> similaritySearchBySession(String chatSessionId,
                                                        String query,
                                                        IntentResolution intentResolution,
                                                        RetrievalPlan retrievalPlan,
                                                        RetrievalSource querySource) {
        return searchScopeResolver.searchBySession(
                chatSessionId, query, intentResolution, retrievalPlan, querySource);
    }

    /** Contract-authoritative retrieval with typed execution outcome. */
    public RetrievalExecutionResult similaritySearchByContract(String chatSessionId,
                                                                String query,
                                                                RetrievalPlan retrievalPlan,
                                                                RetrievalSource querySource) {
        return searchScopeResolver.searchByContract(
                chatSessionId, query, retrievalPlan, querySource);
    }

    // similaritySearchByKnowledgeBaseIds 是旧 eval 门面，生产 Agent runtime 不调用。
    // 评测代码现在直接注入 KnowledgeBaseSimilaritySearcher，避免 RagService 暴露 eval-only API。
}
