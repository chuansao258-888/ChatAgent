package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchPropertiesTest {

    @Test
    void shouldHaveCorrectDefaults() {
        WebSearchProperties props = new WebSearchProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getBraveApiKey()).isNull();
        assertThat(props.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(props.getResponseTimeoutMs()).isEqualTo(30000);
        assertThat(props.getDefaultMaxResults()).isEqualTo(3);
        assertThat(props.getMaxResults()).isEqualTo(3);
        assertThat(props.getMaxResultSnippetChars()).isEqualTo(240);
        assertThat(props.getMaxQueryChars()).isEqualTo(300);
        assertThat(props.getMaxContextTokens()).isEqualTo(4096);
    }

    @Test
    void shouldExposeToolOnlyWithCredential() {
        WebSearchProperties props = new WebSearchProperties();

        assertThat(props.hasConfiguredCredential()).isFalse();
        props.setBraveApiKey("test-key");
        assertThat(props.hasConfiguredCredential()).isTrue();
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
