package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.agent.runtime.CurrentChatSessionHolder;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnKnowledgeHitHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnHolder;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.rag.application.FormattedRetrievalPrompt;
import com.yulong.chatagent.rag.model.RetrievalHit;
import com.yulong.chatagent.rag.application.RagService;
import com.yulong.chatagent.rag.application.RetrievalHitFormatter;
import org.springframework.stereotype.Component;

import java.util.List;

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
            description = "Run similarity search against the files attached to the current chat session and the bound internal assistant knowledge bases. Argument: query."
    )
    public String knowledgeQuery(String query) {
        // 工具参数只包含 query；session/turn/intent 都从后端运行上下文读取。
        // 这一步是安全边界：模型不能自己指定 sessionId 或 turnId，因此无法通过工具参数越权查询别的会话。
        String chatSessionId = CurrentChatSessionHolder.require();
        String turnId = CurrentTurnHolder.require();
        // intentResolution 由 ChatEventProcessor 在本轮开始时写入，用来把 KB 检索限制在意图路由允许的范围内。
        // 如果这里为 null，RAG 层会退回到会话/Agent 默认绑定的知识范围。
        IntentResolution intentResolution = CurrentIntentResolutionHolder.get();
        // intentResolution 会限制可检索知识库范围，避免 KB 意图路由后的越界检索。
        List<RetrievalHit> results = ragService.similaritySearchBySession(chatSessionId, query, intentResolution);
        // 记录本轮是否命中知识，用于 AgentRunResult 和 Dashboard 指标。
        // 注意：这里只表示“检索是否返回结果”，不表示最终回答是否采用了这些结果。
        CurrentTurnKnowledgeHitHolder.recordRetrievalResult(!results.isEmpty());
        // 返回给模型的是带 [1]/[2] 引用标记的证据文本；引用元数据另存，最终回答落库时消费。
        // promptText 和 citations 必须保持同一顺序，否则模型回答里的 [1] 与前端展示的来源会对不上。
        FormattedRetrievalPrompt formatted = retrievalHitFormatter.formatWithCitations(results);
        // citations 不直接拼进 tool_response 的 JSON metadata，而是按 session+turn 暂存。
        // 后面只有当最终 assistant 消息确认保留时，AgentMessageBridge 才会 take 出来写入 message.metadata。
        currentTurnCitationHolder.put(chatSessionId, turnId, formatted.citations());
        return formatted.promptText();
    }
}
