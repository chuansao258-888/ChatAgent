package com.yulong.chatagent.chat.routing;

import com.yulong.chatagent.chat.ChatClientRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelSelectorTest {

    @Test
    void shouldKeepGlmFallbackWhenCandidateKeyMatchesRegisteredBean() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setDefaultModel("deepseek-chat");
        properties.setCandidates(List.of(
                candidate("deepseek-chat", "deepseek-chat", 10),
                candidate("glm-4", "glm-4.6", 20)
        ));

        ChatClient deepseekClient = mock(ChatClient.class);
        ChatClient glmClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("deepseek-chat")).thenReturn(true);
        when(registry.supports("glm-4.6")).thenReturn(true);
        when(registry.getRequired("deepseek-chat")).thenReturn(deepseekClient);
        when(registry.getRequired("glm-4.6")).thenReturn(glmClient);

        ModelSelector selector = new ModelSelector(
                properties,
                registry,
                mock(ModelCapabilityResolver.class),
                new RoutingRuntimeOverridesStore());

        List<ModelTarget> targets = selector.selectChatCandidates(false);

        assertThat(targets)
                .extracting(ModelTarget::id)
                .containsExactly("deepseek-chat", "glm-4");
        assertThat(targets.get(1).chatClient()).isSameAs(glmClient);
        verify(registry).supports("glm-4.6");
    }

    @Test
    void shouldDropFallbackWhenRegisteredBeanKeyDoesNotExist() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setCandidates(List.of(
                candidate("deepseek-chat", "deepseek-chat", 10),
                candidate("glm-4", "glm-4", 20)
        ));

        ChatClient deepseekClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("deepseek-chat")).thenReturn(true);
        when(registry.supports("glm-4")).thenReturn(false);
        when(registry.getRequired("deepseek-chat")).thenReturn(deepseekClient);

        ModelSelector selector = new ModelSelector(
                properties,
                registry,
                mock(ModelCapabilityResolver.class),
                new RoutingRuntimeOverridesStore());

        assertThat(selector.selectChatCandidates(false))
                .extracting(ModelTarget::id)
                .containsExactly("deepseek-chat");
    }

    @Test
    void shouldApplyRuntimeOverridesBeforeFilteringSortingAndThinkingChecks() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setDefaultModel(null);
        properties.setDeepThinkingModel(null);
        properties.setCandidates(List.of(
                candidate("deepseek-chat", "deepseek-chat", 30, false),
                candidate("glm-4", "glm-4.6", 20, true),
                candidate("qwen", "qwen", 10, true)
        ));

        ChatClient deepseekClient = mock(ChatClient.class);
        ChatClient qwenClient = mock(ChatClient.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("deepseek-chat")).thenReturn(true);
        when(registry.supports("glm-4.6")).thenReturn(true);
        when(registry.supports("qwen")).thenReturn(true);
        when(registry.getRequired("deepseek-chat")).thenReturn(deepseekClient);
        when(registry.getRequired("qwen")).thenReturn(qwenClient);

        RoutingRuntimeOverridesStore overrides = new RoutingRuntimeOverridesStore();
        overrides.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                "deepseek-chat",
                true,
                5,
                true,
                "MODEL_OVERRIDE",
                "deepseek-reasoner"));
        overrides.upsert(new RoutingRuntimeOverridesStore.CandidateOverride(
                "glm-4",
                false,
                null,
                null,
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
                .containsExactly("deepseek-chat", "qwen");
        assertThat(normalTargets.get(0).candidate().getPriority()).isEqualTo(5);
        assertThat(normalTargets.get(0).candidate().getSupportsThinking()).isTrue();
        assertThat(normalTargets.get(0).candidate().getThinkingStrategy()).isEqualTo("MODEL_OVERRIDE");
        assertThat(normalTargets.get(0).candidate().getThinkingModel()).isEqualTo("deepseek-reasoner");
        assertThat(deepThinkingTargets)
                .extracting(ModelTarget::id)
                .containsExactly("deepseek-chat", "qwen");
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
