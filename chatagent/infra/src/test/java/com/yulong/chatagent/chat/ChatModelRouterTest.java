package com.yulong.chatagent.chat;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelRouterTest {

    @Test
    void shouldRouteRequestedModelWhenSupported() {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(registry.supports("glm-4.6")).thenReturn(true);
        when(registry.getRequired("glm-4.6")).thenReturn(chatClient);

        ChatModelRouter router = new ChatModelRouter(registry, "deepseek-chat");

        ChatClient routed = router.route("glm-4.6");

        assertThat(routed).isSameAs(chatClient);
    }

    @Test
    void shouldFallbackToDefaultModelWhenRequestIsBlank() {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(registry.getRequired("deepseek-chat")).thenReturn(chatClient);

        ChatModelRouter router = new ChatModelRouter(registry, "deepseek-chat");

        ChatClient routed = router.route(" ");

        assertThat(routed).isSameAs(chatClient);
    }

    @Test
    void shouldFailFastForUnsupportedModel() {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.supports("unknown-model")).thenReturn(false);
        when(registry.availableModels()).thenReturn(Set.of("deepseek-chat", "glm-4.6"));

        ChatModelRouter router = new ChatModelRouter(registry, "deepseek-chat");

        assertThatThrownBy(() -> router.route("unknown-model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported chat model: unknown-model")
                .hasMessageContaining("deepseek-chat");
    }
}
