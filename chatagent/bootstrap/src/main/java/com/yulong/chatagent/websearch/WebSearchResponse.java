package com.yulong.chatagent.websearch;

import java.util.Collections;
import java.util.List;

/**
 * Provider-neutral web search response.
 * <p>
 * Wraps either a successful list of ranked results or a safe error message
 * that can be returned directly to the LLM as tool output.
 */
public record WebSearchResponse(
        List<WebSearchResult> results,
        boolean success,
        String errorMessage
) {

    /**
     * Create a successful response with ranked results.
     * <p>
     * The result list is copied so subsequent mutations to the source list
     * do not affect the response.
     */
    public static WebSearchResponse success(List<WebSearchResult> results) {
        return new WebSearchResponse(
                List.copyOf(results),
                true,
                null
        );
    }

    /**
     * Create a successful response with empty results.
     */
    public static WebSearchResponse empty() {
        return new WebSearchResponse(Collections.emptyList(), true, null);
    }

    /**
     * Create a failure response with a safe error message.
     * <p>
     * The error message should be user-safe and model-readable;
     * it will be returned as tool output text.
     */
    public static WebSearchResponse failure(String errorMessage) {
        return new WebSearchResponse(Collections.emptyList(), false, errorMessage);
    }
}
