package com.yulong.chatagent.agent.tools;

import com.yulong.chatagent.websearch.WebSearchProperties;
import com.yulong.chatagent.websearch.WebSearchRequest;
import com.yulong.chatagent.websearch.WebSearchResponse;
import com.yulong.chatagent.websearch.WebSearchResult;
import com.yulong.chatagent.websearch.searxng.SearXNGHealthChecker;
import com.yulong.chatagent.websearch.searxng.SearXNGWebSearchClient;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Native optional web search tool backed by SearXNG.
 */
@Component
public class WebSearchTools implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private final WebSearchProperties properties;
    private final SearXNGHealthChecker healthChecker;
    private final SearXNGWebSearchClient searchClient;

    public WebSearchTools(WebSearchProperties properties,
                          SearXNGHealthChecker healthChecker,
                          SearXNGWebSearchClient searchClient) {
        this.properties = properties;
        this.healthChecker = healthChecker;
        this.searchClient = searchClient;
    }

    @Override
    public String getName() {
        return "webSearchTool";
    }

    @Override
    public String getDescription() {
        return "Search the public web through the configured SearXNG provider.";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * Runtime availability used by the tool catalog and optional tool pool.
     */
    public boolean isAvailable() {
        return properties.isEnabled() && healthChecker.isReachable();
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "webSearch",
            description = "Search the public web for current or external information. Optional arguments: maxResults, freshness (ANY, DAY, MONTH, YEAR), and comma-separated domains."
    )
    public String webSearch(String query, Integer maxResults, String freshness, String domains) {
        if (!properties.isEnabled()) {
            return "Error: web search is disabled.";
        }
        if (!healthChecker.isReachable()) {
            return "Error: web search provider is not reachable.";
        }

        WebSearchRequest request;
        try {
            request = WebSearchRequest.validate(
                    query,
                    maxResults,
                    freshness,
                    domains,
                    properties.getMaxResults(),
                    properties.getMaxQueryChars(),
                    properties.getDefaultMaxResults()
            );
        } catch (IllegalArgumentException e) {
            return "Error: invalid web search request - " + e.getMessage();
        }

        try {
            WebSearchResponse response = searchClient.search(request);
            return formatResponse(response);
        } catch (Exception e) {
            log.warn("Web search tool execution failed unexpectedly: exception={}", e.getClass().getSimpleName());
            log.debug("Web search tool unexpected failure details", e);
            return "Error: web search is temporarily unavailable.";
        }
    }

    private String formatResponse(WebSearchResponse response) {
        if (response == null) {
            return "Error: web search is temporarily unavailable.";
        }
        if (!response.success()) {
            String message = response.errorMessage() == null || response.errorMessage().isBlank()
                    ? "web search is temporarily unavailable."
                    : response.errorMessage();
            return "Error: " + message;
        }

        List<WebSearchResult> results = response.results();
        if (results == null || results.isEmpty()) {
            return "No web search results found.";
        }

        StringBuilder sb = new StringBuilder("Web search results:\n");
        int index = 1;
        for (WebSearchResult result : results) {
            if ("Note".equalsIgnoreCase(result.title()) && result.url() == null) {
                sb.append("Note: ").append(nullToEmpty(result.snippet())).append("\n");
                continue;
            }

            sb.append(index++).append(". ").append(nullToEmpty(result.title())).append("\n");
            if (hasText(result.url())) {
                sb.append("   URL: ").append(result.url()).append("\n");
            }
            if (hasText(result.snippet())) {
                sb.append("   Snippet: ")
                        .append(trimForToolOutput(result.snippet(), properties.getEffectiveMaxResultSnippetChars()))
                        .append("\n");
            }
            if (hasText(result.engine())) {
                sb.append("   Source: ").append(result.engine()).append("\n");
            }
            if (result.publishedDate() != null) {
                sb.append("   Published: ")
                        .append(DateTimeFormatter.ISO_INSTANT.format(result.publishedDate()))
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimForToolOutput(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }
}
