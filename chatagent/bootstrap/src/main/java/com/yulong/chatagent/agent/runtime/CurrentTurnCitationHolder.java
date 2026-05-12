package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.rag.model.CitationMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 当前 turn 的引用来源暂存器。
 * <p>
 * RAG 工具先把 citations 存到这里；中间的 assistant tool_call 不消费 citations，
 * 等最终 assistant 回答落库时再 take 出来写入消息 metadata。
 */
@Component
public class CurrentTurnCitationHolder {

    // citations 的生命周期跨过“工具执行 -> 模型最终回答 -> assistant 消息落库”多个阶段。
    // 因此这里不用 ThreadLocal，而是用 sessionId::turnId 作为稳定 key，方便不同组件按同一业务轮次取回。
    private final ConcurrentMap<String, List<CitationMetadata>> citationsByTurn = new ConcurrentHashMap<>();

    /**
     * 覆盖写入当前 turn 的 citations。
     * <p>
     * SessionFileTools 在一次检索结束后调用。若一轮内多次调用 put，最新一批引用会覆盖旧引用；
     * 如果业务希望保留多次检索的全部来源，应改用 merge()。
     */
    public void put(String sessionId, String turnId, List<CitationMetadata> citations) {
        // 以 session+turn 作为 key，避免同一 JVM 内多个会话并发运行时引用串线。
        if (!hasValidKey(sessionId, turnId)) {
            return;
        }
        if (citations == null || citations.isEmpty()) {
            // 空结果要主动删除旧值，避免同一个 turn 重试后残留上一批 citations。
            citationsByTurn.remove(key(sessionId, turnId));
            return;
        }
        // LinkedHashSet 负责“去重 + 保序”。保序很重要：模型看到的 [1]/[2] 顺序要和前端引用列表一致。
        citationsByTurn.put(key(sessionId, turnId), List.copyOf(new LinkedHashSet<>(citations)));
    }

    /**
     * 查看当前 turn 的 citations，但不消费。
     * <p>
     * streamDecisionResponse 创建 provisional assistant 消息时使用 peek：
     * 因为这条临时消息之后可能被 tool_call rollback，不能提前把 citations 删除。
     */
    public List<CitationMetadata> peek(String sessionId, String turnId) {
        // peek 用于临时 assistant 消息预览；只有最终消息确认保留时才真正 take。
        if (!hasValidKey(sessionId, turnId)) {
            return List.of();
        }
        List<CitationMetadata> citations = citationsByTurn.get(key(sessionId, turnId));
        return citations == null ? List.of() : List.copyOf(citations);
    }

    /**
     * 取出并删除当前 turn 的 citations。
     * <p>
     * 只有最终 assistant 消息确认要保留时才调用 take，确保同一批引用不会重复挂到多条消息上。
     */
    public List<CitationMetadata> take(String sessionId, String turnId) {
        // take 是一次性消费，防止同一批 citations 同时挂到 tool_call 消息和最终回答上。
        if (!hasValidKey(sessionId, turnId)) {
            return List.of();
        }
        List<CitationMetadata> citations = citationsByTurn.remove(key(sessionId, turnId));
        return citations == null ? List.of() : List.copyOf(citations);
    }

    /**
     * 清理某个 turn 的 citations。
     * <p>
     * 正常 take 后 Map 中已经没有值；这里主要用于异常、rollback 或 MQ 重试前的兜底清理。
     */
    public void clear(String sessionId, String turnId) {
        if (!hasValidKey(sessionId, turnId)) {
            return;
        }
        citationsByTurn.remove(key(sessionId, turnId));
    }

    /**
     * 清理某个 session 下所有暂存引用。
     * <p>
     * 这是失败场景的兜底清理，避免异常中断后残留 citations 被后续 turn 误用。
     */
    public void clearBySession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        String prefix = sessionId.trim() + "::";
        // ConcurrentHashMap 支持并发 removeIf；按 session 前缀清理可以覆盖异常中断遗留的所有 turn。
        citationsByTurn.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * 合并当前 turn 的 citations。
     * <p>
     * 适合一轮内多次 RAG 检索都需要保留引用来源的场景。当前 knowledgeQuery 使用 put，
     * 所以默认语义是“最后一次检索结果作为最终引用集合”。
     */
    public void merge(String sessionId, String turnId, List<CitationMetadata> citations) {
        // 多次检索可能产生多批引用，merge 会去重并保持插入顺序，方便前端按引用序号展示。
        if (!hasValidKey(sessionId, turnId)) {
            return;
        }
        if (citations == null || citations.isEmpty()) {
            return;
        }
        citationsByTurn.compute(key(sessionId, turnId), (ignored, existing) -> {
            LinkedHashSet<CitationMetadata> merged = new LinkedHashSet<>();
            if (existing != null) {
                merged.addAll(existing);
            }
            merged.addAll(citations);
            return new ArrayList<>(merged);
        });
    }

    /**
     * sessionId 和 turnId 必须同时存在，缺任一项都不能安全定位到一个业务轮次。
     */
    private boolean hasValidKey(String sessionId, String turnId) {
        return StringUtils.hasText(sessionId) && StringUtils.hasText(turnId);
    }

    /**
     * 用 sessionId::turnId 组成 Map key。
     * <p>
     * 只用 sessionId 会混合同一会话的多轮；只用 turnId 又不利于跨会话隔离。
     */
    private String key(String sessionId, String turnId) {
        return sessionId.trim() + "::" + turnId.trim();
    }
}
