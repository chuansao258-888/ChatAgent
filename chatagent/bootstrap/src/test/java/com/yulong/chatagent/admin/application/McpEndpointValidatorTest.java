package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.enums.McpProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpEndpointValidatorTest {

    @Test
    void shouldAllowHttpsPublicEndpoint() {
        McpEndpointValidator validator = new McpEndpointValidator(new MockEnvironment());

        String normalized = validator.validateAndNormalize(McpProtocol.HTTP, "https://example.com/mcp");

        assertThat(normalized).isEqualTo("https://example.com/mcp");
    }

    @Test
    void shouldRejectLoopbackOutsideDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        McpEndpointValidator validator = new McpEndpointValidator(environment);

        assertThatThrownBy(() -> validator.validateAndNormalize(McpProtocol.HTTP, "http://127.0.0.1:8080"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Only https endpoints are allowed");
    }

    @Test
    void shouldAllowLocalhostInDefaultProfile() {
        MockEnvironment environment = new MockEnvironment();
        McpEndpointValidator validator = new McpEndpointValidator(environment);

        String normalized = validator.validateAndNormalize(McpProtocol.SSE, "http://127.0.0.1:8080/sse");

        assertThat(normalized).isEqualTo("http://127.0.0.1:8080/sse");
    }

    @Test
    void shouldRejectMetadataEndpoint() {
        McpEndpointValidator validator = new McpEndpointValidator(new MockEnvironment());

        assertThatThrownBy(() -> validator.validateAndNormalize(McpProtocol.HTTP, "https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Metadata endpoints");
    }
}
