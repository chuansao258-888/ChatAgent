package com.yulong.chatagent.websearch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for the native web search tool.
 * <p>
 * Bound to {@code chatagent.web-search} in application.yaml.
 * Defaults to disabled so the application starts without a SearXNG instance.
 */
@Component
@ConfigurationProperties(prefix = "chatagent.web-search")
@Data
public class WebSearchProperties {

    /**
     * Master switch. When false, the web search tool is not exposed.
     */
    private boolean enabled = false;

    /**
     * Base URL of the SearXNG instance (e.g. {@code http://localhost:8888}).
     */
    private String searxngBaseUrl = "http://localhost:8888";

    /**
     * HTTP connect timeout in milliseconds.
     */
    private int connectTimeoutMs = 2000;

    /**
     * HTTP response timeout in milliseconds.
     */
    private int responseTimeoutMs = 8000;

    /**
     * Default number of results when the caller does not specify.
     */
    private int defaultMaxResults = 5;

    /**
     * Hard upper cap on the number of results (backend clamp limit).
     */
    private int maxResults = 8;

    /**
     * Maximum allowed query length in characters.
     */
    private int maxQueryChars = 300;

    /**
     * SearXNG safesearch level: 0=None, 1=Moderate (default), 2=Strict.
     * Values outside [0, 2] are clamped at access time.
     */
    private int safeSearch = 1;

    /**
     * Return the safe-search level clamped to the valid SearXNG range [0, 2].
     */
    public int getEffectiveSafeSearch() {
        return Math.max(0, Math.min(safeSearch, 2));
    }
}
