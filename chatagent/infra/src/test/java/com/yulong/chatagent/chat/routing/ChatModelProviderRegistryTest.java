package com.yulong.chatagent.chat.routing;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatModelProviderRegistryTest {

    @Test
    void shouldRegisterDeepSeekBindingsFromChatModelWhenApiBeanIsUnavailable() {
        DeepSeekApi deepSeekApi = mock(DeepSeekApi.class);
        DeepSeekChatModel deepSeekChatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        ChatModelProviderRegistry registry = new ChatModelProviderRegistry(
                provider(deepSeekChatModel),
                provider(null),
                provider((ZhiPuAiChatModel) null),
                provider((ZhiPuAiApi) null),
                provider(ObservationRegistry.NOOP));

        assertThat(registry.find("deepseek-v4-flash"))
                .get()
                .isInstanceOfSatisfying(ChatModelProviderRegistry.DeepSeekBinding.class,
                        binding -> assertThat(binding.api()).isSameAs(deepSeekApi));
        assertThat(registry.find("deepseek-v4-pro"))
                .get()
                .isInstanceOf(ChatModelProviderRegistry.DeepSeekBinding.class);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }
}
