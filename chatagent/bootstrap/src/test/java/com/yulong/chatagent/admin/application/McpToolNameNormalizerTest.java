package com.yulong.chatagent.admin.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolNameNormalizerTest {

    private final McpToolNameNormalizer normalizer = new McpToolNameNormalizer();

    @Test
    void shouldReplaceIllegalCharacters() {
        assertThat(normalizer.normalizeToolName("google", "web-search"))
                .isEqualTo("mcp_google_web_search");
    }

    @Test
    void shouldCapNamesAtSixtyFourCharacters() {
        String name = normalizer.normalizeToolName("google", "this.is.a.very.long.tool.name.with.characters/that/need/to/be/trimmed");

        assertThat(name).startsWith("mcp_google_");
        assertThat(name.length()).isLessThanOrEqualTo(64);
    }
}
