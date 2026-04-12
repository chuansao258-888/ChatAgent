package com.yulong.chatagent.rag.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.rag.vector.milvus.model.MilvusSearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LlmRetrievalRerankerTest {

    @Mock
    private ChatModelRouter chatModelRouter;

    private LlmRetrievalReranker reranker;

    @BeforeEach
    void setUp() {
        RerankerProperties properties = new RerankerProperties();
        properties.setMaxChunkChars(900);
        reranker = new LlmRetrievalReranker(TestPromptLoader.create(), chatModelRouter, properties, new ObjectMapper());
    }

    @Test
    void shouldIncludeDocumentSignalsInPrompt() {
        String prompt = reranker.buildUserPrompt("leave policy", List.of(
                MilvusSearchHit.builder()
                        .chunkId("chunk-1")
                        .documentId("doc-1")
                        .documentName("HR Handbook")
                        .chunkIndex(2)
                        .documentKeywords(List.of("leave policy"))
                        .documentQuestions(List.of("How many leave days can be carried over?"))
                        .contextText("Annual leave section")
                        .content("Employees may carry over up to five days.")
                        .build()
        ));

        assertThat(prompt).contains("documentKeywords: leave policy");
        assertThat(prompt).contains("documentQuestions: How many leave days can be carried over?");
    }
}
