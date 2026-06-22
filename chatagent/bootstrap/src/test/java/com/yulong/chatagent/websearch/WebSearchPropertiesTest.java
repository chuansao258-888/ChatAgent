package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchPropertiesTest {

    @Test
    void shouldHaveCorrectDefaults() {
        WebSearchProperties props = new WebSearchProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getSearxngBaseUrl()).isEqualTo("http://localhost:8888");
        assertThat(props.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(props.getResponseTimeoutMs()).isEqualTo(8000);
        assertThat(props.getDefaultMaxResults()).isEqualTo(3);
        assertThat(props.getMaxResults()).isEqualTo(3);
        assertThat(props.getMaxResultSnippetChars()).isEqualTo(240);
        assertThat(props.getMaxQueryChars()).isEqualTo(300);
        assertThat(props.getSafeSearch()).isEqualTo(1);
    }

    @Test
    void shouldClampSafeSearchToValidRange() {
        WebSearchProperties props = new WebSearchProperties();

        // Default is valid
        assertThat(props.getEffectiveSafeSearch()).isEqualTo(1);

        // Below range clamps to 0
        props.setSafeSearch(-1);
        assertThat(props.getEffectiveSafeSearch()).isEqualTo(0);

        // Above range clamps to 2
        props.setSafeSearch(5);
        assertThat(props.getEffectiveSafeSearch()).isEqualTo(2);

        // Boundary values pass through
        props.setSafeSearch(0);
        assertThat(props.getEffectiveSafeSearch()).isEqualTo(0);
        props.setSafeSearch(2);
        assertThat(props.getEffectiveSafeSearch()).isEqualTo(2);
    }

    @Test
    void shouldClampMaxResultSnippetCharsToPositiveValue() {
        WebSearchProperties props = new WebSearchProperties();

        assertThat(props.getEffectiveMaxResultSnippetChars()).isEqualTo(240);

        props.setMaxResultSnippetChars(24);
        assertThat(props.getEffectiveMaxResultSnippetChars()).isEqualTo(24);

        props.setMaxResultSnippetChars(0);
        assertThat(props.getEffectiveMaxResultSnippetChars()).isEqualTo(1);

        props.setMaxResultSnippetChars(-10);
        assertThat(props.getEffectiveMaxResultSnippetChars()).isEqualTo(1);
    }
}
