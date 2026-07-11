package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnExecutionContractHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.agent.runtime.contract.QuerySpec;
import com.yulong.chatagent.agent.runtime.contract.RetrievalPlan;
import com.yulong.chatagent.agent.runtime.contract.RetrievalRoutePlan;
import com.yulong.chatagent.agent.runtime.contract.RetrievalSource;
import com.yulong.chatagent.agent.runtime.contract.TurnExecutionContract;
import com.yulong.chatagent.rag.application.FormattedRetrievalPrompt;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.model.RetrievalExecutionOutcome;
import com.yulong.chatagent.rag.model.RetrievalExecutionResult;
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.rag.application.RagService;
import com.yulong.chatagent.rag.application.RetrievalHitFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 会话文件和绑定知识库的固定检索工具。
 * <p>
 * 这是 Agent runtime 中最核心的 RAG 工具：模型只传入 query，真实 sessionId/turnId
 * 由 ThreadLocal 后端上下文提供，避免把内部 ID 暴露给模型或被 prompt injection 伪造。
 * <p>
 * 调用链可以按三段理解：
 * <ol>
 *     <li>模型在决策流里发出 SessionFileSearchTool 的 tool_call，只提供 query。</li>
 *     <li>Spring AI ToolCallingManager 反射调用 knowledgeQuery(query)。</li>
 *     <li>方法把检索证据作为 tool_response 返回给模型，把引用元数据暂存给最终消息落库使用。</li>
 * </ol>
 */
@Component
public class SessionFileTools implements Tool {

    private static final Pattern SESSION_FILE_QUERY = Pattern.compile(
            "(?is)\\b(?:attachment|attached|uploaded|session\\s+(?:file|note|briefing)|file\\s+I\\s+just\\s+attached)\\b"
                    + "|\\.(?:txt|md|markdown|pdf|docx?|xlsx?|csv)\\b"
    );

    private final RagService ragService;
    private final RetrievalHitFormatter retrievalHitFormatter;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;

    public SessionFileTools(RagService ragService,
                            RetrievalHitFormatter retrievalHitFormatter,
                            CurrentTurnCitationHolder currentTurnCitationHolder) {
        this.ragService = ragService;
        this.retrievalHitFormatter = retrievalHitFormatter;
        this.currentTurnCitationHolder = currentTurnCitationHolder;
    }

    @Override
    public String getName() {
        return "SessionFileSearchTool";
    }

    @Override
    public String getDescription() {
        return "Perform semantic retrieval against the files attached to the current chat session and the internal assistant knowledge bases bound to this conversation.";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "SessionFileSearchTool",
            description = "Run similarity search against the current turn's backend-authorized session files and knowledge bases. Arguments: query and optional opaque routeKey."
    )
    public String knowledgeQuery(
            @org.springframework.ai.tool.annotation.ToolParam(description = "Retrieval query") String query,
            @org.springframework.ai.tool.annotation.ToolParam(
                    description = "Opaque backend-issued route key",
                    required = false) String routeKey) {
        // 工具参数只包含 query；session/turn/intent 都从后端运行上下文读取。
        // 这一步是安全边界：模型不能自己指定 sessionId 或 turnId，因此无法通过工具参数越权查询别的会话。
        String chatSessionId = CurrentChatSessionHolder.require();
        String turnId = CurrentTurnHolder.require();
        // intentResolution 由 ChatEventProcessor 在本轮开始时写入，用来把 KB 检索限制在意图路由允许的范围内。
        // 如果这里为 null，RAG 层会退回到会话/Agent 默认绑定的知识范围。
        IntentResolution intentResolution = CurrentIntentResolutionHolder.get();
        // intentResolution 会限制可检索知识库范围，避免 KB 意图路由后的越界检索。
        TurnExecutionContract executionContract = CurrentTurnExecutionContractHolder.get();
        RetrievalExecutionResult executionResult = null;
        List<RetrievalHit> results;
        if (executionContract == null) {
            results = prioritizeSessionFileHitsWhenRequested(
                    query, ragService.similaritySearchBySession(chatSessionId, query, intentResolution));
        } else {
            ContractRetrievalRequest request = resolveContractRequest(executionContract, query, routeKey);
            executionResult = ragService.similaritySearchByContract(
                    chatSessionId, request.query(), request.plan(), request.source());
            if (executionResult.outcome() == RetrievalExecutionOutcome.FAILED) {
                CurrentTurnKnowledgeHitHolder.recordRetrievalResult(executionResult, executionContract);
                currentTurnCitationHolder.recordRetrievalResult(
                        chatSessionId, turnId, executionResult, executionContract);
                throw new IllegalStateException(
                        "Contract retrieval failed: " + executionResult.reasonCode());
            }
            results = prioritizeSessionFileHitsWhenRequested(request.query(), executionResult.hits());
            if (!results.equals(executionResult.hits())) {
                executionResult = new RetrievalExecutionResult(
                        executionResult.outcome(),
                        executionResult.requestedSource(),
                        executionResult.actualSources(),
                        executionResult.policyFallbackApplied(),
                        results,
                        executionResult.reasonCode());
            }
        }
        // 记录本轮是否命中知识，用于 AgentRunResult 和 Dashboard 指标。
        // 注意：这里只表示“检索是否返回结果”，不表示最终回答是否采用了这些结果。
        if (executionResult == null) {
            CurrentTurnKnowledgeHitHolder.recordRetrievalResult(!results.isEmpty());
        } else {
            CurrentTurnKnowledgeHitHolder.recordRetrievalResult(executionResult, executionContract);
            currentTurnCitationHolder.recordRetrievalResult(
                    chatSessionId, turnId, executionResult, executionContract);
        }
        // 返回给模型的是带 [1]/[2] 引用标记的证据文本；引用元数据另存，最终回答落库时消费。
        // promptText 和 citations 必须保持同一顺序，否则模型回答里的 [1] 与前端展示的来源会对不上。
        FormattedRetrievalPrompt formatted = executionResult == null
                ? retrievalHitFormatter.formatWithCitations(results)
                : retrievalHitFormatter.formatExecutionResult(executionResult);
        // citations 不直接拼进 tool_response 的 JSON metadata，而是按 session+turn 暂存。
        // 后面只有当最终 assistant 消息确认保留时，AgentMessageBridge 才会 take 出来写入 message.metadata。
        int firstCitationNumber = currentTurnCitationHolder.appendAndGetFirstCitationNumber(
                chatSessionId,
                turnId,
                formatted.citations()
        );
        if (firstCitationNumber == 1 || formatted.citations().isEmpty()) {
            return formatted.promptText();
        }
        // 一轮内再次检索时继续编号，避免不同 tool_response 都从 [1] 开始而让最终引用错位。
        return executionResult == null
                ? retrievalHitFormatter.formatWithCitations(results, firstCitationNumber).promptText()
                : retrievalHitFormatter.formatExecutionResult(executionResult, firstCitationNumber).promptText();
    }

    /** Source-compatible convenience entry point for direct callers and tests. */
    public String knowledgeQuery(String query) {
        return knowledgeQuery(query, null);
    }

    private ContractRetrievalRequest resolveContractRequest(TurnExecutionContract contract,
                                                            String query,
                                                            String routeKey) {
        RetrievalPlan retrieval = contract.retrieval();
        if (retrieval == null || retrieval.routes().isEmpty()) {
            return new ContractRetrievalRequest(
                    query, retrieval, resolveQuerySource(contract, query));
        }
        RetrievalRoutePlan route;
        if (!StringUtils.hasText(routeKey)) {
            if (retrieval.routes().size() != 1) {
                throw new IllegalArgumentException(
                        "A routeKey is required for multi-route retrieval");
            }
            route = retrieval.routes().get(0);
        } else {
            route = retrieval.routes().stream()
                    .filter(candidate -> candidate.key().equals(routeKey.trim()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown retrieval routeKey"));
        }
        if (contract.queryPlan() == null
                || route.queryIndex() < 0
                || route.queryIndex() >= contract.queryPlan().queries().size()) {
            throw new IllegalStateException("Retrieval route references an invalid query");
        }
        QuerySpec spec = contract.queryPlan().queries().get(route.queryIndex());
        if (spec == null || spec.source() != route.source() || !StringUtils.hasText(spec.text())) {
            throw new IllegalStateException("Retrieval route does not match its query plan");
        }
        RetrievalPlan routePlan = new RetrievalPlan(
                retrieval.mode(),
                route.source(),
                route.scopedKbIds(),
                route.fallbackPolicy(),
                retrieval.citationRequired());
        return new ContractRetrievalRequest(spec.text().trim(), routePlan, route.source());
    }

    private record ContractRetrievalRequest(String query,
                                            RetrievalPlan plan,
                                            RetrievalSource source) {
    }

    private RetrievalSource resolveQuerySource(TurnExecutionContract contract, String query) {
        if (contract.queryPlan() != null && StringUtils.hasText(query)) {
            for (QuerySpec spec : contract.queryPlan().queries()) {
                if (spec != null && StringUtils.hasText(spec.text())
                        && spec.text().trim().equals(query.trim())) {
                    return spec.source();
                }
            }
        }
        return contract.retrieval() == null ? RetrievalSource.NONE : contract.retrieval().source();
    }

    private List<RetrievalHit> prioritizeSessionFileHitsWhenRequested(String query, List<RetrievalHit> results) {
        if (!StringUtils.hasText(query)
                || results == null
                || results.size() < 2
                || !SESSION_FILE_QUERY.matcher(query).find()) {
            return results;
        }
        List<RetrievalHit> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparingInt(
                hit -> hit != null && hit.sourceType() == RagSourceType.SESSION_FILE ? 0 : 1));
        return ordered;
    }
}
