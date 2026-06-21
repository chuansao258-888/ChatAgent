package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class VlmVdpPropertiesBindingTest {

    @Test
    void shouldBindZaiMultimodalModelFromApplicationYaml() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> propertySource : loader.load(
                "application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addFirst(propertySource);
        }

        VlmVdpProperties properties = Binder.get(environment)
                .bind("chatagent.rag.vdp.vlm", VlmVdpProperties.class)
                .orElseThrow(() -> new IllegalStateException("VLM properties were not bound"));

        assertThat(properties.getClientId()).isEqualTo("glm-4.6v-flash");
        assertThat(properties.getModelId()).isEqualTo("glm-4.6v-flash");
        assertThat(properties.getTimeoutMs()).isEqualTo(30000L);
        assertThat(environment.getProperty("spring.ai.zhipuai.base-url"))
                .isEqualTo("https://api.z.ai/api/coding/paas");
        assertThat(environment.getProperty("spring.ai.zhipuai.chat.options.model"))
                .isEqualTo("glm-4.6v-flash");
    }
}
