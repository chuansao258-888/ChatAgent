package com.yulong.chatagent.chat.routing;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingLLMServiceTest {

    @Test
    void syncRoutingShouldNotApplyFirstPacketTimeoutToFullResponse() {
        ChatRoutingProperties properties = new ChatRoutingProperties();
        properties.setFirstPacketTimeoutSeconds(0);
        ModelSelector selector = mock(ModelSelector.class);
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

        when(selector.selectChatCandidates(false))
                .thenReturn(List.of(new ModelTarget("deepseek-chat", null, chatClient)));
        when(healthStore.tryAcquire("deepseek-chat"))
                .thenReturn(new ModelHealthStore.CallPermit(true, 0L));
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        doAnswer(invocation -> {
            Thread.sleep(100L);
            return responseSpec;
        }).when(requestSpec).call();
        when(responseSpec.chatClientResponse())
                .thenReturn(new ChatClientResponse(chatResponse, Map.of()));

        RoutingLLMService service = new RoutingLLMService(
                selector,
                healthStore,
                properties,
                new RoutingPromptFactory(new ModelCapabilityResolver(mock(ChatModelProviderRegistry.class))),
                mock(ProviderDirectStreamSupport.class));

        ChatResponse response = service.chatWithRouting(new Prompt("hello"), "system", List.of());

        assertThat(response).isSameAs(chatResponse);
        verify(healthStore).markSuccess("deepseek-chat", 0L);
    }
}
