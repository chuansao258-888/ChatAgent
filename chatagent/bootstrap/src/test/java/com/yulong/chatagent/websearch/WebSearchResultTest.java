package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchResultTest {

    @Test
    void shouldExtractHost() {
        var result = new WebSearchResult("T", "https://www.example.com/path", "s", null, "g", 1.0);
        assertThat(result.host()).contains("www.example.com");
    }

    @Test
    void shouldReturnEmptyHostForNullUrl() {
        var result = new WebSearchResult("T", null, "s", null, "g", 1.0);
        assertThat(result.host()).isEmpty();
    }

    @Test
    void shouldReturnEmptyHostForInvalidUrl() {
        var result = new WebSearchResult("T", "not a url", "s", null, "g", 1.0);
        assertThat(result.host()).isEmpty();
    }
}
