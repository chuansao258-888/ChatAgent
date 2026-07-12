package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchRequestTest {

    @Test
    void validatesAndMapsBraveFreshness() {
        WebSearchRequest request = WebSearchRequest.validate(
                " release notes ", 3, "week", "spring.io,docs.spring.io", 3, 300, 3);
        assertThat(request.query()).isEqualTo("release notes");
        assertThat(request.braveFreshness()).isEqualTo("pw");
        assertThat(request.providerQuery()).contains("site:spring.io", "site:docs.spring.io");
    }

    @Test
    void rejectsUnknownFreshnessInsteadOfSilentlyDowngrading() {
        assertThatThrownBy(() -> WebSearchRequest.validate(
                "q", 1, "recent", null, 3, 300, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeCountAndAnyInvalidDomain() {
        assertThatThrownBy(() -> WebSearchRequest.validate(
                "q", 4, "ANY", null, 3, 300, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebSearchRequest.validate(
                "q", 1, "ANY", "example.com,http://bad", 3, 300, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProviderQueryPastDocumentedLimits() {
        String words = "word ".repeat(51);
        WebSearchRequest request = WebSearchRequest.validate(
                words, 1, "ANY", null, 3, 400, 3);
        assertThatThrownBy(request::providerQuery).isInstanceOf(IllegalArgumentException.class);
    }
}
