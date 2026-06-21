package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingPromptFactoryTest {

    @Test
    void shouldDisableAnthropicThinkingForNormalZaiRequest() {
        RoutingPromptFactory factory = new RoutingPromptFactory(capabilityResolver());
        ChatRoutingProperties.CandidateConfig candidate = candidate(
                "glm-5.2", "glm-5.2", "ANTHROPIC_THINKING", null);

        Prompt prompt = factory.create(
                new Prompt(List.of(new UserMessage("hello"))),
                null,
                List.of(),
                new ModelTarget("glm-5.2", candidate, mock(ChatClient.class)),
                false);

        assertThat(prompt.getOptions()).isInstanceOf(AnthropicChatOptions.class);
        AnthropicChatOptions options = (AnthropicChatOptions) prompt.getOptions();
        assertThat(options.getThinking()).isNotNull();
        assertThat(options.getThinking().type()).isEqualTo(AnthropicApi.ThinkingType.DISABLED);
        assertThat(options.getThinking().budgetTokens()).isNull();
    }

    @Test
    void shouldEnableAnthropicThinkingForDeepThinkZaiRequest() {
        RoutingPromptFactory factory = new RoutingPromptFactory(capabilityResolver());
        ChatRoutingProperties.CandidateConfig candidate = candidate(
                "glm-5.2", "glm-5.2", "ANTHROPIC_THINKING", null);

        Prompt prompt = factory.create(
                new Prompt(List.of(new UserMessage("hello"))),
                null,
                List.of(),
                new ModelTarget("glm-5.2", candidate, mock(ChatClient.class)),
                true);

        AnthropicChatOptions options = (AnthropicChatOptions) prompt.getOptions();
        assertThat(options.getThinking().type()).isEqualTo(AnthropicApi.ThinkingType.ENABLED);
        assertThat(options.getThinking().budgetTokens()).isEqualTo(1024);
    }

    @Test
    void shouldKeepDeepSeekFlashForNormalRequestAndUseProForDeepThink() {
        RoutingPromptFactory factory = new RoutingPromptFactory(capabilityResolver());
        ChatRoutingProperties.CandidateConfig candidate = candidate(
                "deepseek-v4-flash", "deepseek-v4-flash", "MODEL_OVERRIDE", "deepseek-v4-pro");
        ModelTarget target = new ModelTarget("deepseek-v4-flash", candidate, mock(ChatClient.class));

        Prompt normalPrompt = factory.create(
                new Prompt(List.of(new UserMessage("hello"))), null, List.of(), target, false);
        Prompt deepThinkPrompt = factory.create(
                new Prompt(List.of(new UserMessage("hello"))), null, List.of(), target, true);

        assertThat(normalPrompt.getOptions()).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(((DeepSeekChatOptions) normalPrompt.getOptions()).getModel()).isNull();
        assertThat(((DeepSeekChatOptions) deepThinkPrompt.getOptions()).getModel())
                .isEqualTo("deepseek-v4-pro");
    }

    private static ChatRoutingProperties.CandidateConfig candidate(String id,
                                                                   String springClientKey,
                                                                   String thinkingStrategy,
                                                                   String thinkingModel) {
        ChatRoutingProperties.CandidateConfig candidate = new ChatRoutingProperties.CandidateConfig();
        candidate.setId(id);
        candidate.setSpringClientKey(springClientKey);
        candidate.setSupportsThinking(true);
        candidate.setThinkingStrategy(thinkingStrategy);
        candidate.setThinkingModel(thinkingModel);
        return candidate;
    }

    private static ModelCapabilityResolver capabilityResolver() {
        ModelCapabilityResolver resolver = mock(ModelCapabilityResolver.class);
        when(resolver.supportsThinking(any())).thenReturn(true);
        return resolver;
    }
}
