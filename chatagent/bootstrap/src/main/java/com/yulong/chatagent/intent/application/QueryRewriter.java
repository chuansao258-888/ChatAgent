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
 * Intent-aware query rewriter that leverages LLM to produce cleaner retrieval hints.
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
            return originalQuery.trim();
        }

        // Only KB intents need sophisticated rewriting for better retrieval.
        if (intentResolution.kind() != IntentKind.KB) {
            return originalQuery.trim();
        }

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
