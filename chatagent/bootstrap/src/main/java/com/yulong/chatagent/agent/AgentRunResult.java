package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.rag.model.RetrievalOutcomeMetadata;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeoutException;

/**
 * 单次 Agent 运行的轻量结果摘要。
 * <p>
 * ChatEventProcessor 会把它写入 turn metrics，用于 Dashboard 统计耗时、失败类型和知识命中情况。
 * <p>
 * 它不承载聊天正文，也不影响模型推理，只是一次 Agent run 的观测结果：
 * 成功/失败、耗时、错误分类，以及本轮 RAG 检索是否命中。
 *
 * @param status 本次 Agent run 的最终状态
 * @param durationMs 从 ChatAgent.run() 开始到结束的耗时
 * @param errorType 失败时的粗粒度错误分类；成功时为 null
 * @param knowledgeHit 本轮知识检索是否命中；未尝试检索的非 KB 任务默认视为 true
 */
public record AgentRunResult(
        Status status,
        long durationMs,
        String errorType,
        boolean knowledgeHit,
        RetrievalOutcomeMetadata retrieval
) {

    public AgentRunResult(Status status, long durationMs, String errorType, boolean knowledgeHit) {
        this(status, durationMs, errorType, knowledgeHit, null);
    }

    public enum Status {
        SUCCESS,
        PARTIAL,
        BLOCKED,
        ERROR
    }

    /**
     * 构造成功结果。
     * <p>
     * knowledgeHit 由 CurrentTurnKnowledgeHitHolder 在 ChatAgent.run() 结束前读取，
     * 用于区分“知识库查不到”和“普通非知识任务”。
     */
    public static AgentRunResult success(long durationMs, boolean knowledgeHit) {
        return new AgentRunResult(Status.SUCCESS, durationMs, null, knowledgeHit,
                CurrentTurnKnowledgeHitHolder.metadataSnapshot());
    }

    /**
     * 构造失败结果，并把异常链归类成稳定的 errorType。
     * <p>
     * Dashboard 更适合按少量错误类型聚合，而不是存储完整异常类名或堆栈。
     */
    public static AgentRunResult failure(long durationMs, boolean knowledgeHit, Throwable throwable) {
        return new AgentRunResult(Status.ERROR, durationMs, classifyError(throwable), knowledgeHit,
                CurrentTurnKnowledgeHitHolder.metadataSnapshot());
    }

    /**
     * 构造部分完成结果：run 在仍有有效进展时被预算/轮次耗尽或验证未通过中止，
     * 例如可选工作在工具预算耗尽后带显式限制收尾，或 DeepThink 验证轮次耗尽。
     * <p>
     * 这类结果既不是 SUCCESS（没有完整达成目标），也不是 ERROR（没有抛错），
     * 上层会在最终输出里附带显式的局限性说明。
     *
     * @param errorType 稳定的停止原因，例如 {@code TOOL_BUDGET_EXHAUSTED}；不应为 null
     */
    public static AgentRunResult partial(long durationMs, boolean knowledgeHit, String errorType) {
        return new AgentRunResult(Status.PARTIAL, durationMs, errorType, knowledgeHit,
                CurrentTurnKnowledgeHitHolder.metadataSnapshot());
    }

    /**
     * 构造被阻塞结果：run 因必须等待外部输入而无法继续，最典型的是一次非只读/未知
     * 副作用工具提案需要用户精确确认。这类结果不会自动重试，也不会被渲染成 SUCCESS。
     *
     * @param errorType 稳定的阻塞原因，例如 {@code CONFIRMATION_REQUIRED}；不应为 null
     */
    public static AgentRunResult blocked(long durationMs, boolean knowledgeHit, String errorType) {
        return new AgentRunResult(Status.BLOCKED, durationMs, errorType, knowledgeHit,
                CurrentTurnKnowledgeHitHolder.metadataSnapshot());
    }

    private static String classifyError(Throwable throwable) {
        // 指标层不需要完整异常栈，只需要稳定的错误分类；这里从 root cause 做启发式归类。
        Throwable rootCause = unwrap(throwable);
        if (rootCause == null) {
            return "UNEXPECTED_ERROR";
        }
        if (rootCause instanceof TimeoutException || rootCause.getClass().getSimpleName().contains("Timeout")) {
            // 首包超时、总流式超时、上游请求超时都归到 LLM_TIMEOUT，方便统计模型可用性。
            return "LLM_TIMEOUT";
        }
        String simpleName = rootCause.getClass().getSimpleName();
        if (simpleName.contains("Remote") || simpleName.contains("Http")) {
            // HTTP/Remote 一般表示模型服务、reranker 服务或外部依赖异常。
            return "UPSTREAM_ERROR";
        }
        if (simpleName.contains("Tool")) {
            // 工具执行阶段抛出的异常归为工具错误，和模型流式错误分开看。
            return "TOOL_EXECUTION_ERROR";
        }
        String message = rootCause.getMessage();
        if (StringUtils.hasText(message) && message.toLowerCase().contains("retriev")) {
            // 检索链路有些异常类名不稳定，message 中包含 retriev 时兜底归为 RAG 检索失败。
            return "RETRIEVAL_FAIL";
        }
        return "UNEXPECTED_ERROR";
    }

    private static Throwable unwrap(Throwable throwable) {
        // AgentRunException 等包装异常会把真实原因放在 cause 里；指标分类看最内层 root cause 更稳定。
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
