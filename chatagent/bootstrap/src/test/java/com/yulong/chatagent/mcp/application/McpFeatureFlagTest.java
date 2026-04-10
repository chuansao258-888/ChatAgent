package com.yulong.chatagent.mcp.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpFeatureFlagTest {

    @Test
    void shouldToggleEnabledState() {
        McpFeatureFlag flag = new McpFeatureFlag();
        flag.setEnabled(false);

        assertThat(flag.isEnabled()).isFalse();
    }
}
