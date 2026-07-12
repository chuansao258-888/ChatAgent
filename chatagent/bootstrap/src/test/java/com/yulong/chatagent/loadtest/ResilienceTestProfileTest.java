package com.yulong.chatagent.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigRegistry;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import com.yulong.chatagent.chat.routing.LLMService;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResilienceTestProfileTest {

    @Test
    void shouldKeepCapacityStubPathDisabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ((ConfigurableEnvironment) context.getEnvironment()).addActiveProfile("resilience-test");
            ((AnnotationConfigRegistry) context).register(
                    CapacityStubLlmService.class,
                    CapacityStubChatModelRouter.class,
                    CapacityTestProperties.class);
            LLMService routingService = mock(LLMService.class);
            context.registerBean("routingLLMService", LLMService.class, () -> routingService);
            context.refresh();

            assertThat(context.containsBean("capacityStubLlmService")).isFalse();
            assertThat(context.containsBean("capacityStubChatModelRouter")).isFalse();
            assertThat(context.getBean(LLMService.class)).isSameAs(routingService);
        }
    }

    @Test
    void shouldUseOnlyLoopbackFixtureAndPlaceholderCredentials() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "resilience-test",
                new ClassPathResource("application-resilience-test.yaml"));

        assertThat(value(sources, "spring.ai.anthropic.base-url").toString())
                .contains("127.0.0.1:8890");
        assertThat(value(sources, "spring.ai.deepseek.base-url").toString())
                .contains("127.0.0.1:8890");
        assertThat(value(sources, "spring.ai.anthropic.api-key"))
                .isEqualTo("REDACTED_API_KEY");
        assertThat(value(sources, "spring.ai.deepseek.api-key"))
                .isEqualTo("REDACTED_API_KEY");
    }

    private static Object value(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing property: " + name));
    }
}
