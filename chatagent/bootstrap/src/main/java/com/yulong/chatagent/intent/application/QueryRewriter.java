package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Intent-aware query rewriter that leverages LLM to produce cleaner retrieval hints.
 */
@Component
@Slf4j
public class QueryRewriter {

    private final ChatModelRouter chatModelRouter;
    private final String rewriteModel;

    public QueryRewriter(ChatModelRouter chatModelRouter,
                         @Value("${chatagent.intent.rewrite-model:}") String rewriteModel) {
        this.chatModelRouter = chatModelRouter;
        this.rewriteModel = rewriteModel;
    }

    public String rewrite(String originalQuery, IntentResolution intentResolution) {
        if (!StringUtils.hasText(originalQuery)) {
            return originalQuery;
        }
        if (intentResolution == null || !StringUtils.hasText(intentResolution.pathLabel())) {
            return originalQuery.trim();
        }

        // Only KB intents need sophisticated rewriting for better retrieval
        if (intentResolution.kind() != IntentKind.KB) {
            return originalQuery.trim();
        }

        String prompt = """
                你是搜索查询优化专家。请根据当前命中的意图路径，将用户的原始输入重写为一个更适合在知识库中进行语义检索的完整查询。
                
                # 规则
                1. 补全代词（如“它”、“这个”、“流程”等）为具体的业务对象。
                2. 保留所有专业术语。
                3. 如果原始输入已经很完整，则保持原样。
                4. 结合意图路径提供的上下文进行补全。
                5. 只返回重写后的文本，不要有任何解释。
                
                # 上下文
                命中意图路径: %s
                原始输入: %s
                
                重写后的查询:
                """.formatted(intentResolution.pathLabel(), originalQuery);

        try {
            ChatClient chatClient = chatModelRouter.route(rewriteModel);
            String rewritten = chatClient.prompt(prompt)
                    .call()
                    .content();
            if (StringUtils.hasText(rewritten)) {
                return rewritten.trim();
            }
            log.warn("Query rewriter returned blank content: path={}", intentResolution.pathLabel());
        } catch (Exception e) {
            log.warn("Query rewrite fallback to simple concatenation: path={}, error={}",
                    intentResolution.pathLabel(),
                    e.getMessage());
        }

        return fallbackRewrite(originalQuery, intentResolution);
    }

    private String fallbackRewrite(String originalQuery, IntentResolution intentResolution) {
        if (intentResolution == null || !StringUtils.hasText(intentResolution.pathLabel())) {
            return originalQuery == null ? null : originalQuery.trim();
        }
        return intentResolution.pathLabel() + " | " + originalQuery.trim();
    }
}
