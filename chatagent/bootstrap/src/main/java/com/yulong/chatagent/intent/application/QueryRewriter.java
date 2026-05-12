package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 基于意图识别结果的 query rewrite。
 *
 * 它发生在「意图已经 resolved」之后、真正 dispatch 给 AgentRuntime 之前：
 * 1. KB 意图：调用 LLM，把用户原话改写成更适合知识库检索的查询；
 * 2. TOOL/SYSTEM 等非 KB 意图：不额外调用 LLM，只做确定性的 anchor 补强；
 * 3. 任何异常都会 fallback，不能因为 rewrite 失败阻塞主对话。
 */
@Component
@Slf4j
public class QueryRewriter {

    private final PromptLoader promptLoader;
    private final ChatModelRouter chatModelRouter;
    private final String rewriteModel;

    public QueryRewriter(PromptLoader promptLoader,
                         ChatModelRouter chatModelRouter,
                         @Value("${chatagent.intent.rewrite-model:}") String rewriteModel) {
        this.promptLoader = promptLoader;
        this.chatModelRouter = chatModelRouter;
        this.rewriteModel = rewriteModel;
    }

    public String rewrite(String originalQuery, IntentResolution intentResolution) {
        if (!StringUtils.hasText(originalQuery)) {
            return originalQuery;
        }
        if (intentResolution == null || !StringUtils.hasText(intentResolution.pathLabel())) {
            // 没有明确意图路径时，不要擅自改写，避免给 Agent 注入错误上下文。
            return originalQuery.trim();
        }

        // 只有 KB 意图需要 LLM rewrite，因为它直接影响向量检索 query。
        // 非 KB 意图只需要把叶子意图名补到文本里，让后续 prompt/tool 选择更稳定。
        if (intentResolution.kind() != IntentKind.KB) {
            return enforceAnchor(originalQuery.trim(), intentResolution.pathLabel());
        }

        // prompt 模板在 PromptConstants.INTENT_QUERY_REWRITE 指向的资源里。
        // 传给 LLM 的核心参数只有两个：命中的意图路径 + 用户原始输入。
        String prompt = promptLoader.render(PromptConstants.INTENT_QUERY_REWRITE, Map.of(
                "intentPath", intentResolution.pathLabel(),
                "originalInput", originalQuery
        ));

        try {
            ChatClient chatClient = chatModelRouter.route(rewriteModel);
            String rewritten = chatClient.prompt(prompt)
                    .call()
                    .content();
            if (StringUtils.hasText(rewritten)) {
                // 即使 LLM 改写成功，也再做一次确定性 anchor，避免它漏掉叶子意图关键词。
                return enforceAnchor(rewritten.trim(), intentResolution.pathLabel());
            }
            log.warn("Query rewriter returned blank content: path={}", intentResolution.pathLabel());
        } catch (Exception e) {
            log.warn("Query rewrite fallback to simple concatenation: path={}, error={}",
                    intentResolution.pathLabel(),
                    e.getMessage());
        }

        // LLM 不可用/返回空时，用最简单稳定的方式拼接“意图路径 | 用户原话”。
        return fallbackRewrite(originalQuery, intentResolution);
    }

    private String fallbackRewrite(String originalQuery, IntentResolution intentResolution) {
        if (intentResolution == null || !StringUtils.hasText(intentResolution.pathLabel())) {
            return originalQuery == null ? null : originalQuery.trim();
        }
        return intentResolution.pathLabel() + " | " + originalQuery.trim();
    }

    /**
     * 保证叶子意图名称原样出现在 query 里。
     *
     * 例子：
     * pathLabel = "课程 > 作业 > 延期申请"
     * query = "我想问一下流程"
     * 返回 = "延期申请 我想问一下流程"
     *
     * 这不是语义改写，而是一个确定性安全垫：防止 LLM 或用户短句把关键意图词丢掉。
     */
    private String enforceAnchor(String query, String pathLabel) {
        String leafName = extractLeafName(pathLabel);
        if (leafName != null && !query.contains(leafName)) {
            return leafName + " " + query;
        }
        return query;
    }

    private String extractLeafName(String pathLabel) {
        if (!StringUtils.hasText(pathLabel)) {
            return null;
        }
        // pathLabel 的格式由 IntentResolution.pathLabel() 生成，层级之间用 " > " 拼接。
        int idx = pathLabel.lastIndexOf('>');
        String leaf = idx >= 0 ? pathLabel.substring(idx + 1).trim() : pathLabel.trim();
        return leaf.isBlank() ? null : leaf;
    }
}
