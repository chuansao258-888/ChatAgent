package com.yulong.chatagent.rag.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.rag.ingestion.enhance.DocumentEnhancerProperties;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.SessionIngestionContext;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmDocumentEnhancerTest {

    @Test
    void shouldNeverEnhanceSessionPipeline() {
        LlmDocumentEnhancer enhancer = new LlmDocumentEnhancer(
                TestPromptLoader.create(),
                null,
                new DocumentEnhancerProperties(),
                new ObjectMapper()
        );
        SessionIngestionContext context = SessionIngestionContext.builder()
                .segments(List.of(new ParseSegment("session note", 0, SegmentType.FULL, Map.of())))
                .build();

        DocumentEnhancementResult result = enhancer.enhance(context);

        assertThat(result).isEqualTo(DocumentEnhancementResult.empty());
    }

    @Test
    void shouldKeepSuccessfulMapWindowsWhenOneWindowFails() {
        DocumentEnhancerProperties properties = new DocumentEnhancerProperties();
        properties.setShortDocCharLimit(10);
        properties.setMapWindowMaxChars(15);
        properties.setMaxKeywords(10);
        properties.setMaxQuestions(5);
        LlmDocumentEnhancer enhancer = new LlmDocumentEnhancer(TestPromptLoader.create(), null, properties, new ObjectMapper()) {
            @Override
            DocMetaExtractResult runDocMetaExtract(String text) {
                if (text.contains("window-two")) {
                    throw new IllegalStateException("bad json");
                }
                return new DocMetaExtractResult(
                        List.of("keyword-a", "keyword-b"),
                        List.of("question-a"),
                        Map.of("doc_type", "manual", "contains_pii", false)
                );
            }
        };
        KnowledgeIngestionContext context = KnowledgeIngestionContext.builder()
                .documentId("doc-1")
                .segments(List.of(
                        new ParseSegment("window-one", 0, SegmentType.PAGE, Map.of("pageNumber", 1)),
                        new ParseSegment("window-two", 1, SegmentType.PAGE, Map.of("pageNumber", 2))
                ))
                .build();

        DocumentEnhancementResult result = enhancer.enhance(context);

        assertThat(result.enhancedSegments()).isNull();
        assertThat(result.keywords()).containsExactly("keyword-a", "keyword-b");
        assertThat(result.questions()).containsExactly("question-a");
        assertThat(result.metadata()).containsEntry("doc_type", "manual");
        assertThat(result.metadata()).containsEntry("contains_pii", false);
        assertThat(result.cacheKey()).isNotBlank();
    }
}
