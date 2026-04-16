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

        // Only KB intents need LLM-based rewriting for retrieval optimization.
        // All intents get programmatic anchor enforcement regardless of kind.
        if (intentResolution.kind() != IntentKind.KB) {
            return enforceAnchor(originalQuery.trim(), intentResolution.pathLabel());
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
                return enforceAnchor(rewritten.trim(), intentResolution.pathLabel());
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

    /**
     * Guarantees the intent leaf name appears verbatim in the query text.
     * If the leaf is absent, prepends it — this is a deterministic safety net
     * that applies regardless of intent kind (KB, TOOL, SYSTEM).
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
        int idx = pathLabel.lastIndexOf('>');
        String leaf = idx >= 0 ? pathLabel.substring(idx + 1).trim() : pathLabel.trim();
        return leaf.isBlank() ? null : leaf;
    }
}
