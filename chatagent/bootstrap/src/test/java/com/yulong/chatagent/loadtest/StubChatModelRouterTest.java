package com.yulong.chatagent.loadtest;

import com.yulong.chatagent.chat.ChatClientRegistry;
import com.yulong.chatagent.chat.ChatModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StubChatModelRouterTest {

    @Test
    void routeShouldReturnNonStubChatClientRegardlessOfRequestedModel() {
        LoadTestProperties props = new LoadTestProperties();
        props.setMockTtftMs(1L);
        // Empty registry is fine: the stub overrides route(...) and never reads it.
        ChatClientRegistry registry = new ChatClientRegistry(Map.of());
        StubChatModelRouter router = new StubChatModelRouter(registry, props);

        ChatClient client = router.route("any-model-id");

        assertThat(client).isNotNull();
        // Every requested model gets the same cached stub.
        assertThat(router.route("another-model")).isSameAs(client);
        assertThat(router.route(null)).isSameAs(client);
    }

    @Test
    void shouldExtendChatModelRouter() {
        assertThat(new StubChatModelRouter(new ChatClientRegistry(Map.of()), new LoadTestProperties()))
                .isInstanceOf(ChatModelRouter.class);
    }

    @Test
    void stubChatModelCallShouldReturnCannedContent() {
        StubChatModel model = new StubChatModel(1L, 1L);
        var response = model.call(new Prompt("anything"));

        assertThat(response.getResult().getOutput().getText()).isNotBlank();
    }
}
