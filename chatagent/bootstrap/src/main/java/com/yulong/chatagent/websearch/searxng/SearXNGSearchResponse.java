package com.yulong.chatagent.websearch.searxng;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * SearXNG JSON search response envelope.
 * <p>
 * Maps the top-level fields from {@code GET /search?format=json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearXNGSearchResponse(
        List<SearXNGSearchResult> results,
        List<String> suggestions,
        Integer numberOfResults
) {
}
