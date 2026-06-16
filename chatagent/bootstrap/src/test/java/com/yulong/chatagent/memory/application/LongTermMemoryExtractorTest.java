package com.yulong.chatagent.memory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.conversation.summary.AtomicConversationTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryExtractorTest {

    @Mock
    private PromptLoader promptLoader;

    @Mock
    private ChatModelRouter chatModelRouter;

    private ObjectMapper objectMapper;

    private LongTermMemoryExtractor extractor;

    private static final List<AtomicConversationTurn> TURNS = List.of(
            new AtomicConversationTurn("t1", 1L, 3L, List.of("I prefer short answers"), "OK")
    );

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        extractor = new LongTermMemoryExtractor(
                promptLoader, chatModelRouter, objectMapper, "deepseek-chat", "memory/l3-extractor.md");
    }

    // ── parseResponse tests ──────────────────────────────────────────

    @Test
    void shouldParseValidJsonResponse() {
        String json = """
                {
                  "memories": [
                    {"type": "preference", "content": "User prefers short answers.", "tags": ["communication"]},
                    {"type": "fact", "content": "User works at NTU.", "tags": ["background"]}
                  ]
                }
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).hasSize(2);
        assertThat(result.memories().get(0).type()).isEqualTo("preference");
        assertThat(result.memories().get(0).content()).isEqualTo("User prefers short answers.");
        assertThat(result.memories().get(0).tags()).containsExactly("communication");
        assertThat(result.memories().get(1).type()).isEqualTo("fact");
    }

    @Test
    void shouldReturnEmptyMemoriesWhenArrayIsEmpty() {
        String json = """
                {"memories": []}
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).isEmpty();
    }

    @Test
    void shouldFailOnInvalidJson() {
        ExtractionResult result = extractor.parseResponse("not json at all");

        assertThat(result.success()).isFalse();
        assertThat(result.memories()).isEmpty();
    }

    @Test
    void shouldDropInvalidType() {
        String json = """
                {
                  "memories": [
                    {"type": "opinion", "content": "Some content", "tags": []},
                    {"type": "fact", "content": "Valid fact", "tags": []}
                  ]
                }
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).hasSize(1);
        assertThat(result.memories().get(0).type()).isEqualTo("fact");
    }

    @Test
    void shouldDropEmptyContent() {
        String json = """
                {
                  "memories": [
                    {"type": "preference", "content": "", "tags": []},
                    {"type": "preference", "content": "   ", "tags": []}
                  ]
                }
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).isEmpty();
    }

    @Test
    void shouldNormalizeTags() {
        String json = """
                {
                  "memories": [
                    {"type": "fact", "content": "A fact", "tags": ["  Hello World  ", "UPPER_CASE", "already-kebab", "dup", "dup"]}
                  ]
                }
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).hasSize(1);
        assertThat(result.memories().get(0).tags())
                .containsExactly("hello-world", "upper-case", "already-kebab", "dup");
    }

    @Test
    void shouldHandleNullTags() {
        String json = """
                {
                  "memories": [
                    {"type": "fact", "content": "A fact"}
                  ]
                }
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories().get(0).tags()).isEmpty();
    }

    @Test
    void shouldStripCodeFence() {
        String fenced = """
                ```json
                {"memories": [{"type": "fact", "content": "Test", "tags": []}]}
                ```
                """;
        ExtractionResult result = extractor.parseResponse(fenced);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).hasSize(1);
    }

    @Test
    void shouldDropMissingTypeWithoutDiscardingOtherValidMemories() {
        String json = """
                {
                  "memories": [
                    {"content": "Missing type field", "tags": []},
                    {"type": "fact", "content": "Valid fact", "tags": []}
                  ]
                }
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).hasSize(1);
        assertThat(result.memories().get(0).type()).isEqualTo("fact");
        assertThat(result.memories().get(0).content()).isEqualTo("Valid fact");
    }

    @Test
    void shouldFailWhenMemoriesKeyMissing() {
        String json = """
                {"data": []}
                """;
        ExtractionResult result = extractor.parseResponse(json);

        assertThat(result.success()).isFalse();
    }

    // ── extract (end-to-end with mock) tests ─────────────────────────

    @Test
    void shouldReturnFailureOnBlankModelOutput() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatModelRouter.route("deepseek-chat")).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("");
        when(promptLoader.render(anyString(), any())).thenReturn("prompt");

        ExtractionResult result = extractor.extract(TURNS);

        assertThat(result.success()).isFalse();
        assertThat(result.memories()).isEmpty();
    }

    @Test
    void shouldReturnFailureOnModelException() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

        when(chatModelRouter.route("deepseek-chat")).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("API down"));
        when(promptLoader.render(anyString(), any())).thenReturn("prompt");

        ExtractionResult result = extractor.extract(TURNS);

        assertThat(result.success()).isFalse();
        assertThat(result.memories()).isEmpty();
    }

    @Test
    void shouldReturnSuccessWithValidModelOutput() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatModelRouter.route("deepseek-chat")).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("""
                {"memories": [{"type": "preference", "content": "Likes short answers", "tags": ["style"]}]}
                """);
        when(promptLoader.render(anyString(), any())).thenReturn("prompt");

        ExtractionResult result = extractor.extract(TURNS);

        assertThat(result.success()).isTrue();
        assertThat(result.memories()).hasSize(1);
        assertThat(result.memories().get(0).type()).isEqualTo("preference");
    }

    @Test
    void shouldRenderConfiguredPromptCandidate() {
        when(promptLoader.render(anyString(), any())).thenReturn("prompt");

        extractor.buildPrompt(TURNS);

        verify(promptLoader).render(org.mockito.ArgumentMatchers.eq("memory/l3-extractor.md"), any());
    }
}
