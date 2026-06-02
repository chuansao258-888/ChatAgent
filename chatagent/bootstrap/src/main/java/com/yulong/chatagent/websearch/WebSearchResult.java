package com.yulong.chatagent.websearch;

import java.time.Instant;
import java.util.Optional;

/**
 * A single web search result item.
 * <p>
 * Title and URL are required; all other fields are optional.
 * Instances are immutable records produced by the search client.
 */
public record WebSearchResult(
        String title,
        String url,
        String snippet,
        Instant publishedDate,
        String engine,
        double score
) {

    /**
     * Return the host extracted from the URL (e.g. "www.example.com")
     * for local domain post-filtering.
     * <p>
     * This returns the full host; callers comparing against a domain filter
     * should check whether the host equals or ends with the requested domain.
     */
    public Optional<String> host() {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return Optional.ofNullable(host);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
