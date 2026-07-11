package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentPolicyProfileLoaderTest {

    @Test
    void shouldLoadFrozenProfileBoundToCurrentRuntime() {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        properties.setMode(IntentPolicyMode.ENFORCE);

        IntentPolicyProfile profile = loader(properties, "deepseek-v4-flash").loadConfigured();

        assertThat(profile.version()).isEqualTo("v1");
        assertThat(profile.promptVersion()).isEqualTo(StructuredIntentClassifier.PROMPT_VERSION);
        assertThat(profile.featureVersion()).isEqualTo(IntentCandidateGenerator.FEATURE_VERSION);
        assertThat(profile.classifierTemperature()).isEqualTo(StructuredIntentClassifier.TEMPERATURE);
        assertThat(profile.classifierMaxTokens()).isEqualTo(StructuredIntentClassifier.MAX_TOKENS);
    }

    @Test
    void shouldRejectProfileWhenConfiguredClassifierChanges() {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        properties.setMode(IntentPolicyMode.ENFORCE);

        assertThatThrownBy(() -> loader(properties, "another-model").loadConfigured())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtime bindings");
    }

    @Test
    void shouldFailEnforceStartupWhenProfileDoesNotExist() {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        properties.setMode(IntentPolicyMode.ENFORCE);
        properties.setProfileVersion("missing");

        assertThatThrownBy(() -> loader(properties, "deepseek-v4-flash").validateEnforceProfile())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    private IntentPolicyProfileLoader loader(IntentPolicyProperties properties, String model) {
        return new IntentPolicyProfileLoader(
                new DefaultResourceLoader(), new ObjectMapper(), properties, model);
    }
}
