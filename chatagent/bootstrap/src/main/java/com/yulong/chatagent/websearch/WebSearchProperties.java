package com.yulong.chatagent.websearch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the native web search tool.
 * <p>
 * Bound to {@code chatagent.web-search} in application.yaml.
 * Defaults to disabled; Brave credentials remain backend-only.
 * <p>
 * Registered by {@link com.yulong.chatagent.websearch.config.WebSearchConfiguration}
 * via {@code @EnableConfigurationProperties}; do not add {@code @Component}.
 */
@ConfigurationProperties(prefix = "chatagent.web-search")
@Data
public class WebSearchProperties {

    /**
     * Master switch. When false, the web search tool is not exposed.
     */
    private boolean enabled = false;

    /** Brave credential and optional test-only endpoint override. */
    private String braveApiKey;
    private String braveCountry;
    private String braveSearchLang;
    private String braveBaseUrl;

    /**
     * HTTP connect timeout in milliseconds.
     */
    private int connectTimeoutMs = 2000;

    /**
     * HTTP response timeout in milliseconds.
     */
    private int responseTimeoutMs = 30000;

    /**
     * Default number of results when the caller does not specify.
     */
    private int defaultMaxResults = 3;

    /**
     * Hard upper cap on the number of results (backend clamp limit).
     */
    private int maxResults = 3;

    /**
     * Maximum snippet characters returned in model-facing tool output.
     */
    private int maxResultSnippetChars = 240;

    /**
     * Maximum allowed query length in characters.
     */
    private int maxQueryChars = 300;

    /** Brave context and per-run limits. */
    private int maxContextTokens = 4096;
    private int maxCallsPerRun = 3;
    private int maxResponseBytes = 1_048_576;

    /** Whether native search may be exposed without a paid startup probe. */
    public boolean hasConfiguredCredential() {
        return braveApiKey != null && !braveApiKey.isBlank();
    }

    public String getEffectiveBraveEndpoint() {
        return braveBaseUrl == null || braveBaseUrl.isBlank()
                ? "https://api.search.brave.com/res/v1/llm/context" : braveBaseUrl;
    }

    /**
     * Return a positive snippet character cap for model-facing tool output.
     */
    public int getEffectiveMaxResultSnippetChars() {
        return Math.max(1, maxResultSnippetChars);
    }
}
