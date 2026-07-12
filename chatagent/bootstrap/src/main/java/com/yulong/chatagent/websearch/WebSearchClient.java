package com.yulong.chatagent.websearch;

/** Provider transport seam for the stable native web-search tool. */
public interface WebSearchClient {
    WebSearchResponse search(WebSearchRequest request);
}
