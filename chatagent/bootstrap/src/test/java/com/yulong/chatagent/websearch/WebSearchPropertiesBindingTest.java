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
        assertThat(properties.getSearxngBaseUrl()).isEqualTo("http://localhost:8888");
        assertThat(properties.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(properties.getResponseTimeoutMs()).isEqualTo(8000);
        assertThat(properties.getDefaultMaxResults()).isEqualTo(5);
        assertThat(properties.getMaxResults()).isEqualTo(8);
        assertThat(properties.getMaxQueryChars()).isEqualTo(300);
        assertThat(properties.getSafeSearch()).isEqualTo(1);
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
