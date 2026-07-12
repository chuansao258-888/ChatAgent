package com.yulong.chatagent.websearch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchPropertiesBindingTest {

    @Test
    void shouldBindWebSearchPropertiesFromApplicationYaml() throws Exception {
        WebSearchProperties properties = bindFromApplicationYaml();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBraveApiKey()).isEmpty();
        assertThat(properties.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(properties.getResponseTimeoutMs()).isEqualTo(30000);
        assertThat(properties.getDefaultMaxResults()).isEqualTo(3);
        assertThat(properties.getMaxResults()).isEqualTo(3);
        assertThat(properties.getMaxResultSnippetChars()).isEqualTo(240);
        assertThat(properties.getMaxQueryChars()).isEqualTo(300);
        assertThat(properties.getMaxContextTokens()).isEqualTo(4096);
    }

    private static WebSearchProperties bindFromApplicationYaml() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> ps : loader.load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addFirst(ps);
        }
        return Binder.get(environment)
                .bind("chatagent.web-search", WebSearchProperties.class)
                .orElseThrow(() -> new IllegalStateException("chatagent.web-search was not bound from application.yaml"));
    }
}
