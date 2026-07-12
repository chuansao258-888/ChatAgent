package com.yulong.chatagent.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.AnnotationConfigRegistry;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import com.yulong.chatagent.chat.routing.LLMService;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies the capacity-test profile scoping of the stub beans without starting
 * the full application context (which would require Redis/PG/RabbitMQ).
 *
 * <p>The stubs are {@code @Profile("capacity-test")} so they must only be registered
 * when the {@code capacity-test} profile is active. This is the regression check: a
 * normal startup (without the profile) must not instantiate the stubs.</p>
 */
class CapacityTestProfileTest {

    @Test
    void profileShouldDisableBothLimiterLayersByDefault() throws IOException {
        var sources = new YamlPropertySourceLoader().load(
                "capacity-test",
                new ClassPathResource("application-capacity-test.yaml"));

        assertThat(sources).anySatisfy(source -> {
            assertThat(source.getProperty("chatagent.rate-limit.entry.enabled")).isEqualTo(false);
            assertThat(source.getProperty("chatagent.rate-limit.agent-run.enabled")).isEqualTo(false);
            assertThat(source.getProperty("chatagent.capacity-test.mock-ttft-ms")).isEqualTo(100);
            assertThat(source.getProperty("spring.ai.anthropic.api-key")).isEqualTo("REDACTED_API_KEY");
            assertThat(source.getProperty("spring.ai.anthropic.base-url")).isEqualTo("http://127.0.0.1:1");
        });
    }

    @Test
    void stubBeansShouldOnlyExistUnderCapacityTestProfile() {
        // With the profile active: stub bean classes are candidates.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ((ConfigurableEnvironment) ctx.getEnvironment()).addActiveProfile("capacity-test");
        ((AnnotationConfigRegistry) ctx).register(CapacityStubLlmService.class, CapacityStubChatModelRouter.class, CapacityTestProperties.class);
        // CapacityStubChatModelRouter needs a ChatClientRegistry + CapacityTestProperties.
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                (org.springframework.beans.factory.support.DefaultListableBeanFactory) ctx.getBeanFactory();
        factory.registerSingleton("chatClientRegistry",
                new com.yulong.chatagent.chat.ChatClientRegistry(java.util.Map.of()));
        ctx.refresh();

        assertThat(ctx.getBean(CapacityStubLlmService.class)).isNotNull();
        assertThat(ctx.getBean(LLMService.class)).isInstanceOf(CapacityStubLlmService.class);
        assertThat(ctx.getBean(CapacityStubChatModelRouter.class)).isNotNull();
        assertThat(ctx.getBean(CapacityTestProperties.class).getMockTtftMs()).isEqualTo(400L);
        ctx.close();
    }

    @Test
    void capacityStubShouldRemainPrimaryWhenRoutingServiceAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ((ConfigurableEnvironment) context.getEnvironment()).addActiveProfile("capacity-test");
            ((AnnotationConfigRegistry) context).register(
                    CapacityStubLlmService.class, CapacityTestProperties.class);
            context.registerBean("routingLLMService", LLMService.class, () -> mock(LLMService.class));
            context.refresh();

            assertThat(context.getBean(LLMService.class)).isInstanceOf(CapacityStubLlmService.class);
        }
    }

    @Test
    void capacityAndResilienceProfilesShouldBeMutuallyExclusive() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        ((ConfigurableEnvironment) context.getEnvironment())
                .setActiveProfiles("capacity-test", "resilience-test");
        ((AnnotationConfigRegistry) context).register(CapacityTestMemoryConfiguration.class);

        assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("capacity-test and resilience-test profiles are mutually exclusive");
        context.close();
    }

    @Test
    void stubBeansShouldNotBeRegisteredWithoutCapacityTestProfile() {
        // Without the profile: registering the classes must NOT produce beans.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ((AnnotationConfigRegistry) ctx).register(CapacityStubLlmService.class, CapacityStubChatModelRouter.class, CapacityTestProperties.class);
        ctx.refresh();

        assertThat(ctx.containsBean("capacityStubLlmService")).isFalse();
        assertThat(ctx.containsBean("capacityStubChatModelRouter")).isFalse();
        assertThat(ctx.containsBean("capacityTestProperties")).isFalse();
        ctx.close();
    }

    @Test
    void stubBeansShouldNotBeRegisteredUnderResilienceTestProfile() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ((ConfigurableEnvironment) ctx.getEnvironment()).addActiveProfile("resilience-test");
        ((AnnotationConfigRegistry) ctx).register(
                CapacityStubLlmService.class, CapacityStubChatModelRouter.class, CapacityTestProperties.class);
        ctx.refresh();

        assertThat(ctx.containsBean("capacityStubLlmService")).isFalse();
        assertThat(ctx.containsBean("capacityStubChatModelRouter")).isFalse();
        ctx.close();
    }

    @Test
    void stubClassesShouldBeProfileAnnotated() {
        assertThat(CapacityStubLlmService.class.isAnnotationPresent(Profile.class)).isTrue();
        assertThat(CapacityStubLlmService.class.getAnnotation(Profile.class).value())
                .containsExactly("capacity-test");
        assertThat(CapacityStubChatModelRouter.class.isAnnotationPresent(Profile.class)).isTrue();
        assertThat(CapacityStubChatModelRouter.class.getAnnotation(Profile.class).value())
                .containsExactly("capacity-test");
    }
}
