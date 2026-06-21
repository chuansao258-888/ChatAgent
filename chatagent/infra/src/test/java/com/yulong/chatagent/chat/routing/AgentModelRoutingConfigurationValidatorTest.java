package com.yulong.chatagent.chat.routing;

import com.yulong.chatagent.chat.ChatClientRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentModelRoutingConfigurationValidatorTest {

    @Test
    void shouldAcceptConfiguredPrimaryAndFallbackCandidates() {
        ChatRoutingProperties properties = validProperties();
        ChatClientRegistry registry = registrySupporting("glm-5.2", "deepseek-v4-flash");
        ModelCapabilityResolver capabilityResolver = capabilityResolver(true);

        AgentModelRoutingConfigurationValidator validator =
                new AgentModelRoutingConfigurationValidator(properties, registry, capabilityResolver);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBlankPrimaryModel() {
        ChatRoutingProperties properties = validProperties();
        properties.setAgentPrimaryModel(" ");

        assertThatThrownBy(validator(properties)::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.routing.agent-primary-model must not be blank");
    }

    @Test
    void shouldRejectDuplicatePrimaryAndFallbackModel() {
        ChatRoutingProperties properties = validProperties();
        properties.setAgentFallbackModel("glm-5.2");

        assertThatThrownBy(validator(properties)::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be distinct");
    }

    @Test
    void shouldRejectUnknownCandidateId() {
        ChatRoutingProperties properties = validProperties();
        properties.setAgentFallbackModel("missing");

        assertThatThrownBy(validator(properties)::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references unknown candidate id: missing");
    }

    @Test
    void shouldRejectDisabledCandidate() {
        ChatRoutingProperties properties = validProperties();
        properties.getCandidates().get(1).setEnabled(false);

        assertThatThrownBy(validator(properties)::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references disabled candidate id: deepseek-v4-flash");
    }

    @Test
    void shouldRejectUnregisteredSpringClientKey() {
        ChatRoutingProperties properties = validProperties();
        properties.getCandidates().get(1).setSpringClientKey("missing-client");

        assertThatThrownBy(validator(properties)::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring-client-key is not registered: missing-client");
    }

    @Test
    void shouldRejectCandidateThatCannotServeDeepThink() {
        ChatRoutingProperties properties = validProperties();

        assertThatThrownBy(new AgentModelRoutingConfigurationValidator(
                properties,
                registrySupporting("glm-5.2", "deepseek-v4-flash"),
                capabilityResolver(false))::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must support DeepThink routing");
    }

    private static AgentModelRoutingConfigurationValidator validator(ChatRoutingProperties properties) {
        return new AgentModelRoutingConfigurationValidator(
                properties,
                registrySupporting("glm-5.2", "deepseek-v4-flash"),
                capabilityResolver(true));
    }

    private static ChatRoutingProperties validProperties() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setAgentPrimaryModel("glm-5.2");
        properties.setAgentFallbackModel("deepseek-v4-flash");
        properties.setCandidates(List.of(
                candidate("glm-5.2", "glm-5.2"),
                candidate("deepseek-v4-flash", "deepseek-v4-flash")
        ));
        return properties;
    }

    private static ChatRoutingProperties.CandidateConfig candidate(String id, String springClientKey) {
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setId(id);
        candidate.setSpringClientKey(springClientKey);
        candidate.setEnabled(true);
        candidate.setSupportsThinking(true);
        return candidate;
    }

    private static ChatClientRegistry registrySupporting(String... keys) {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        for (String key : keys) {
            when(registry.supports(key)).thenReturn(true);
        }
        return registry;
    }

    private static ModelCapabilityResolver capabilityResolver(boolean supportsThinking) {
        ModelCapabilityResolver resolver = mock(ModelCapabilityResolver.class);
        when(resolver.supportsThinking(any())).thenReturn(supportsThinking);
        return resolver;
    }
}
