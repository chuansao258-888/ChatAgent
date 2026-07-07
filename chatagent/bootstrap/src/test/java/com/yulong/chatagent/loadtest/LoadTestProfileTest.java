package com.yulong.chatagent.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.AnnotationConfigRegistry;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the load-test profile scoping of the stub beans without starting
 * the full application context (which would require Redis/PG/RabbitMQ).
 *
 * <p>The stubs are {@code @Profile("load-test")} so they must only be registered
 * when the {@code load-test} profile is active. This is the regression check: a
 * normal startup (without the profile) must not instantiate the stubs.</p>
 */
class LoadTestProfileTest {

    @Test
    void stubBeansShouldOnlyExistUnderLoadTestProfile() {
        // With the profile active: stub bean classes are candidates.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ((ConfigurableEnvironment) ctx.getEnvironment()).addActiveProfile("load-test");
        ((AnnotationConfigRegistry) ctx).register(StubLLMService.class, StubChatModelRouter.class, LoadTestProperties.class);
        // StubChatModelRouter needs a ChatClientRegistry + LoadTestProperties.
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                (org.springframework.beans.factory.support.DefaultListableBeanFactory) ctx.getBeanFactory();
        factory.registerSingleton("chatClientRegistry",
                new com.yulong.chatagent.chat.ChatClientRegistry(java.util.Map.of()));
        ctx.refresh();

        assertThat(ctx.getBean(StubLLMService.class)).isNotNull();
        assertThat(ctx.getBean(StubChatModelRouter.class)).isNotNull();
        assertThat(ctx.getBean(LoadTestProperties.class).getMockTtftMs()).isEqualTo(400L);
        ctx.close();
    }

    @Test
    void stubBeansShouldNotBeRegisteredWithoutLoadTestProfile() {
        // Without the profile: registering the classes must NOT produce beans.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ((AnnotationConfigRegistry) ctx).register(StubLLMService.class, StubChatModelRouter.class, LoadTestProperties.class);
        // LoadTestProperties has no @Profile so it registers regardless; that's fine.
        ctx.refresh();

        assertThat(ctx.containsBean("stubLLMService")).isFalse();
        assertThat(ctx.containsBean("stubChatModelRouter")).isFalse();
        // The properties holder is profile-agnostic and always present.
        assertThat(ctx.getBean(LoadTestProperties.class)).isNotNull();
        ctx.close();
    }

    @Test
    void stubClassesShouldBeProfileAnnotated() {
        assertThat(StubLLMService.class.isAnnotationPresent(Profile.class)).isTrue();
        assertThat(StubLLMService.class.getAnnotation(Profile.class).value())
                .containsExactly("load-test");
        assertThat(StubChatModelRouter.class.isAnnotationPresent(Profile.class)).isTrue();
        assertThat(StubChatModelRouter.class.getAnnotation(Profile.class).value())
                .containsExactly("load-test");
    }
}
