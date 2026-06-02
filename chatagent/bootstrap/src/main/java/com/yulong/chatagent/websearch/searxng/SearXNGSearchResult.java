package com.yulong.chatagent.websearch.searxng;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Single result item in the SearXNG JSON response.
 * <p>
 * Fields follow the SearXNG search API schema. Only the fields we use are mapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearXNGSearchResult(
        String title,
        String url,
        String content,
        String engine,
        Double score,
        String publishedDate
) {
}
