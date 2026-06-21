package com.yulong.chatagent.chat.routing;

import com.yulong.chatagent.chat.ChatClientRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelSelectorTest {

    @Test
    void shouldSelectConfiguredAgentPrimaryBeforeFallback() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setAgentPrimaryModel("glm-5.2");
        properties.setAgentFallbackModel("deepseek-v4-flash");
        properties.setCandidates(List.of(
                candidate("deepseek-v4-flash", "deepseek-v4-flash", 1, true),
                candidate("glm-5.2", "glm-5.2", 99, true),
                candidate("third", "third", 0, true)
        ));

        ChatClient glmClient = mock(ChatClient.class);
        ChatClient deepseekClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("glm-5.2")).thenReturn(true);
        when(registry.supports("deepseek-v4-flash")).thenReturn(true);
        when(registry.getRequired("glm-5.2")).thenReturn(glmClient);
        when(registry.getRequired("deepseek-v4-flash")).thenReturn(deepseekClient);

        ModelSelector selector = new ModelSelector(
                properties,
                registry,
                capabilityResolver(true),
                new RoutingRuntimeOverridesStore());

        List<ModelTarget> targets = selector.selectChatCandidates(false);

        assertThat(targets)
                .extracting(ModelTarget::id)
                .containsExactly("glm-5.2", "deepseek-v4-flash");
        assertThat(targets.get(0).chatClient()).isSameAs(glmClient);
        assertThat(targets.get(1).chatClient()).isSameAs(deepseekClient);
    }

    @Test
    void shouldDropConfiguredCandidateWhenRegisteredBeanKeyDoesNotExist() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setAgentPrimaryModel("glm-5.2");
        properties.setAgentFallbackModel("deepseek-v4-flash");
        properties.setCandidates(List.of(
                candidate("glm-5.2", "glm-5.2", 10, true),
                candidate("deepseek-v4-flash", "missing-client", 20, true)
        ));

        ChatClient glmClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("glm-5.2")).thenReturn(true);
        when(registry.supports("missing-client")).thenReturn(false);
        when(registry.getRequired("glm-5.2")).thenReturn(glmClient);

        ModelSelector selector = new ModelSelector(
                properties,
                registry,
                capabilityResolver(true),
                new RoutingRuntimeOverridesStore());

        assertThat(selector.selectChatCandidates(false))
                .extracting(ModelTarget::id)
                .containsExactly("glm-5.2");
    }

    @Test
    void shouldApplyRuntimeOverridesBeforeFilteringAndThinkingChecksWithoutReorderingThirdModel() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setAgentPrimaryModel("glm-5.2");
        properties.setAgentFallbackModel("deepseek-v4-flash");
        properties.setCandidates(List.of(
                candidate("glm-5.2", "glm-5.2", 30, true),
                candidate("deepseek-v4-flash", "deepseek-v4-flash", 20, false),
                candidate("third", "third", 1, true)
        ));

        ChatClient deepseekReasonerClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("deepseek-v4-flash")).thenReturn(true);
        when(registry.getRequired("deepseek-v4-flash")).thenReturn(deepseekReasonerClient);

        RoutingRuntimeOverridesStore overrides = new RoutingRuntimeOverridesStore();
        overrides.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                "deepseek-v4-flash",
                true,
                5,
                true,
                "MODEL_OVERRIDE",
                "deepseek-v4-pro"));
        overrides.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                "glm-5.2",
                false,
                null,
                null,
                null,
                null));
        overrides.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                "third",
                true,
                -100,
                true,
                null,
                null));

        ModelSelector selector = new ModelSelector(
                properties,
                registry,
                new ModelCapabilityResolver(mock(ChatModelProviderRegistry.class)),
                overrides);

        List<ModelTarget> normalTargets = selector.selectChatCandidates(false);
        List<ModelTarget> deepThinkingTargets = selector.selectChatCandidates(true);

        assertThat(normalTargets)
                .extracting(ModelTarget::id)
                .containsExactly("deepseek-v4-flash");
        assertThat(normalTargets.get(0).candidate().getPriority()).isEqualTo(5);
        assertThat(normalTargets.get(0).candidate().getSupportsThinking()).isTrue();
        assertThat(normalTargets.get(0).candidate().getThinkingStrategy()).isEqualTo("MODEL_OVERRIDE");
        assertThat(normalTargets.get(0).candidate().getThinkingModel()).isEqualTo("deepseek-v4-pro");
        assertThat(deepThinkingTargets)
                .extracting(ModelTarget::id)
                .containsExactly("deepseek-v4-flash");
    }

    @Test
    void shouldUseSameConfiguredPairForDeepThinking() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setAgentPrimaryModel("glm-5.2");
        properties.setAgentFallbackModel("deepseek-v4-flash");
        properties.setDeepThinkingModel("third");
        properties.setCandidates(List.of(
                candidate("third", "third", 1, true),
                candidate("glm-5.2", "glm-5.2", 10, true),
                candidate("deepseek-v4-flash", "deepseek-v4-flash", 20, true)
        ));

        ChatClient glmClient = mock(ChatClient.class);
        ChatClient deepseekClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("glm-5.2")).thenReturn(true);
        when(registry.supports("deepseek-v4-flash")).thenReturn(true);
        when(registry.getRequired("glm-5.2")).thenReturn(glmClient);
        when(registry.getRequired("deepseek-v4-flash")).thenReturn(deepseekClient);

        ModelSelector selector = new ModelSelector(
                properties,
                registry,
                capabilityResolver(true),
                new RoutingRuntimeOverridesStore());

        assertThat(selector.selectChatCandidates(true))
                .extracting(ModelTarget::id)
                .containsExactly("glm-5.2", "deepseek-v4-flash");
    }

    private static ModelCapabilityResolver capabilityResolver(boolean supportsThinking) {
        ModelCapabilityResolver resolver = mock(ModelCapabilityResolver.class);
        when(resolver.supportsThinking(org.mockito.ArgumentMatchers.any())).thenReturn(supportsThinking);
        return resolver;
    }

    private static ChatRoutingProperties.CandidateConfig candidate(String id, String springClientKey, int priority) {
        return candidate(id, springClientKey, priority, null);
    }

    private static ChatRoutingProperties.CandidateConfig candidate(String id,
                                                                   String springClientKey,
                                                                   int priority,
                                                                   Boolean supportsThinking) {
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setId(id);
        candidate.setSpringClientKey(springClientKey);
        candidate.setPriority(priority);
        candidate.setEnabled(true);
        candidate.setSupportsThinking(supportsThinking);
        return candidate;
    }
}
