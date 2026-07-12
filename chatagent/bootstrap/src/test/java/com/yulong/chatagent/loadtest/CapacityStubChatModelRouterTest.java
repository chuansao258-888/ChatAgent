package com.yulong.chatagent.loadtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.chat.ChatClientRegistry;
import com.yulong.chatagent.chat.ChatModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityStubChatModelRouterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void routeShouldReturnNonStubChatClientRegardlessOfRequestedModel() {
        CapacityTestProperties props = new CapacityTestProperties();
        props.setMockTtftMs(1L);
        // Empty registry is fine: the stub overrides route(...) and never reads it.
        ChatClientRegistry registry = new ChatClientRegistry(Map.of());
        CapacityStubChatModelRouter router = new CapacityStubChatModelRouter(registry, props);

        ChatClient client = router.route("any-model-id");

        assertThat(client).isNotNull();
        // Every requested model gets the same cached stub.
        assertThat(router.route("another-model")).isSameAs(client);
        assertThat(router.route(null)).isSameAs(client);
    }

    @Test
    void shouldExtendChatModelRouter() {
        assertThat(new CapacityStubChatModelRouter(new ChatClientRegistry(Map.of()), new CapacityTestProperties()))
                .isInstanceOf(ChatModelRouter.class);
    }

    @Test
    void stubChatModelCallShouldReturnCannedContent() {
        CapacityStubChatModel model = new CapacityStubChatModel(1L, 1L);
        var response = model.call(new Prompt("anything"));

        assertThat(response.getResult().getOutput().getText())
                .contains("压测")
                .contains("模拟回答");
    }

    @Test
    void stubChatModelCallShouldReturnStructuredSummaryJsonForSummarizerPrompt() throws Exception {
        CapacityStubChatModel model = new CapacityStubChatModel(1L, 1L);
        var response = model.call(new Prompt("You are a structured memory summarizer for an enterprise AI assistant."));

        Map<String, Object> parsed = OBJECT_MAPPER.readValue(
                response.getResult().getOutput().getText(),
                new TypeReference<>() {
                });

        assertThat(parsed)
                .containsKeys("summary", "facts", "decisions", "open_tasks", "entities");
        assertThat(parsed.get("summary").toString()).contains("压测");
    }
}
