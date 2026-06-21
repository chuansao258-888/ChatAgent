package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    @Mock
    private ChatModelRouter chatModelRouter;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ChatClient chatClient;

    private QueryRewriter queryRewriter;

    @BeforeEach
    void setUp() {
        queryRewriter = new QueryRewriter(TestPromptLoader.create(), chatModelRouter, "rewrite-model");
    }

    @Test
    void shouldRewriteQueryUsingLlmForKbIntent() {
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().name("报销制度").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );
        when(chatModelRouter.route("rewrite-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString()).call().content()).thenReturn("如何申请差旅报销？");

        String result = queryRewriter.rewrite("怎么弄？", resolution);

        assertThat(result).isEqualTo("报销制度 如何申请差旅报销？");
    }

    @Test
    void shouldFallbackToSimpleConcatenationWhenLlmFails() {
        IntentResolution resolution = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().name("报销制度").build()),
                List.of("kb-1"),
                ScopePolicy.STRICT,
                List.of(),
                null
        );
        when(chatModelRouter.route("rewrite-model")).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenThrow(new RuntimeException("LLM Down"));

        String result = queryRewriter.rewrite("怎么弄？", resolution);

        assertThat(result).isEqualTo("报销制度 | 怎么弄？");
    }

    @Test
    void shouldReturnOriginalQueryForNonKbIntent() {
        IntentResolution resolution = new IntentResolution(
                IntentKind.TOOL,
                List.of(IntentNodeDTO.builder().name("查询订单").build()),
                List.of(),
                ScopePolicy.STRICT,
                List.of("dataBaseTool"),
                null
        );

        String result = queryRewriter.rewrite("查一下今天的订单量", resolution);

        assertThat(result).isEqualTo("查询订单 查一下今天的订单量");
    }

    @Test
    void shouldReturnOriginalQueryWhenResolutionIsNull() {
        String result = queryRewriter.rewrite("你好", null);
        assertThat(result).isEqualTo("你好");
    }
}
