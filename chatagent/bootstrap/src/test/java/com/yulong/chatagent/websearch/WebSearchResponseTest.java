package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchResponseTest {

    @Test
    void shouldCreateSuccessResponse() {
        var result = new WebSearchResult("Title", "https://example.com", "snippet", null, "google", 0.9);
        var response = WebSearchResponse.success(List.of(result));

        assertThat(response.success()).isTrue();
        assertThat(response.results()).hasSize(1);
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void shouldCreateEmptyResponse() {
        var response = WebSearchResponse.empty();

        assertThat(response.success()).isTrue();
        assertThat(response.results()).isEmpty();
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void shouldCreateFailureResponse() {
        var response = WebSearchResponse.failure("web search is temporarily unavailable");

        assertThat(response.success()).isFalse();
        assertThat(response.results()).isEmpty();
        assertThat(response.errorMessage()).isEqualTo("web search is temporarily unavailable");
    }

    @Test
    void successResultsShouldBeImmutable() {
        var result = new WebSearchResult("T", "https://example.com", "s", null, "g", 1.0);
        var response = WebSearchResponse.success(new ArrayList<>(List.of(result)));

        assertThatThrownBy(() -> response.results().add(result))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void successShouldNotBeAffectedBySourceListMutation() {
        var result1 = new WebSearchResult("T1", "https://a.com", "s", null, "g", 1.0);
        var result2 = new WebSearchResult("T2", "https://b.com", "s", null, "g", 0.8);
        var source = new ArrayList<>(List.of(result1, result2));

        var response = WebSearchResponse.success(source);

        // Mutate the source list after construction
        source.clear();

        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).title()).isEqualTo("T1");
    }

    @Test
    void failureResultsShouldBeImmutable() {
        var response = WebSearchResponse.failure("error");

        assertThatThrownBy(() -> response.results().add(
                new WebSearchResult("T", "https://x.com", "s", null, "g", 1.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
