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

        // Only KB intents need sophisticated rewriting for better retrieval.
        if (intentResolution.kind() != IntentKind.KB) {
            return originalQuery.trim();
        }

        String prompt = """
                You are a search-query optimization expert. Rewrite the user's original input into a complete query that is better suited for semantic retrieval in the knowledge base, using the matched intent path as context.

                # Rules
                1. Expand pronouns such as "it", "this", or "the process" into the concrete business object when the intent path makes it clear.
                2. Preserve all domain-specific terminology.
                3. If the original input is already complete, keep it unchanged.
                4. Use the context from the intent path to fill in omitted details.
                5. Return only the rewritten query text with no explanation.

                # Context
                Matched intent path: %s
                Original input: %s

                Rewritten query:
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
