package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoutingPropertiesBindingTest {

    @Test
    void shouldBindRoutingPropertiesFromApplicationYaml() throws Exception {
        ChatRoutingProperties properties = bindFromApplicationYaml();

        assertThat(properties.getDefaultModel()).isEqualTo("glm-5.2");
        assertThat(properties.getDeepThinkingModel()).isEqualTo("glm-5.2");
        assertThat(properties.getAgentPrimaryModel()).isEqualTo("glm-5.2");
        assertThat(properties.getAgentFallbackModel()).isEqualTo("deepseek-v4-flash");
        assertThat(properties.getFirstPacketTimeoutSeconds()).isEqualTo(60);
        assertThat(properties.getDecisionTotalTimeoutSeconds()).isEqualTo(180);
        assertThat(properties.getStreamTotalTimeoutSeconds()).isEqualTo(300);
        assertThat(properties.getHttpConnectTimeoutSeconds()).isEqualTo(10);
        assertThat(properties.getHttpReadTimeoutSeconds()).isEqualTo(65);
        assertThat(properties.getHealth().getFailureThreshold()).isEqualTo(3);
        assertThat(properties.getHealth().getOpenDurationMs()).isEqualTo(300_000L);
        assertThat(properties.getHealth().getHalfOpenFlightTimeoutMs()).isEqualTo(120_000L);
        assertThat(properties.getObservability().getOpenCircuitWarningRatio()).isEqualTo(0.5D);
        assertThat(properties.getObservability().isDownWhenNoRoutableCandidates()).isTrue();
        assertThat(properties.getObservability().isOutOfServiceWhenAllRoutableCandidatesOpen()).isTrue();
        assertThat(properties.getObservability().isWarnOnOrphanOverrides()).isTrue();

        assertThat(properties.getCandidates())
                .extracting(ChatRoutingProperties.CandidateConfig::getId)
                .containsExactly("glm-5.2", "deepseek-v4-flash");
        ChatRoutingProperties.CandidateConfig glm = properties.getCandidates().get(0);
        assertThat(glm.getSpringClientKey()).isEqualTo("glm-5.2");
        assertThat(glm.getPriority()).isEqualTo(5);
        assertThat(glm.getEnabled()).isTrue();
        assertThat(glm.getSupportsThinking()).isTrue();
        assertThat(glm.getThinkingStrategy()).isEqualTo("ANTHROPIC_THINKING");

        ChatRoutingProperties.CandidateConfig deepseek = properties.getCandidates().get(1);
        assertThat(deepseek.getSpringClientKey()).isEqualTo("deepseek-v4-flash");
        assertThat(deepseek.getPriority()).isEqualTo(10);
        assertThat(deepseek.getEnabled()).isTrue();
        assertThat(deepseek.getSupportsThinking()).isTrue();
        assertThat(deepseek.getThinkingStrategy()).isEqualTo("MODEL_OVERRIDE");
        assertThat(deepseek.getThinkingModel()).isEqualTo("deepseek-v4-pro");
    }

    private static ChatRoutingProperties bindFromApplicationYaml() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
                "application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addFirst(propertySource);
        }
        return Binder.get(environment)
                .bind("chat.routing", ChatRoutingProperties.class)
                .orElseThrow(() -> new IllegalStateException("chat.routing was not bound from application.yaml"));
    }
}
