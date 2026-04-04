package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.KnowledgeIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.SessionIngestionContext;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseIngestionContextTest {

    @Test
    void shouldPreferEnhancedSegmentsOverParserSegments() {
        KnowledgeIngestionContext context = KnowledgeIngestionContext.builder()
                .segments(List.of(new ParseSegment("parser segment", 0, SegmentType.FULL, Map.of())))
                .enhancedSegments(List.of(new ParseSegment("enhanced segment", 0, SegmentType.FULL, Map.of())))
                .build();

        List<ParseSegment> resolved = context.resolveChunkSegments();

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).text()).isEqualTo("enhanced segment");
    }

    @Test
    void shouldReturnParserSegmentsWhenNoEnhancedSegmentsExist() {
        SessionIngestionContext context = SessionIngestionContext.builder()
                .segments(List.of(new ParseSegment("parser segment", 0, SegmentType.FULL, Map.of())))
                .build();

        List<ParseSegment> resolved = context.resolveChunkSegments();

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).text()).isEqualTo("parser segment");
    }

    @Test
    void shouldReturnEmptyWhenNoSegmentsExist() {
        SessionIngestionContext context = SessionIngestionContext.builder().build();

        assertThat(context.resolveChunkSegments()).isEmpty();
        assertThat(context.resolveDocumentPrefix(100)).isEmpty();
    }

    @Test
    void shouldResolveDocumentPrefixFromSegmentCurrencyWithCharLimit() {
        KnowledgeIngestionContext context = KnowledgeIngestionContext.builder()
                .segments(List.of(
                        new ParseSegment("parser one", 0, SegmentType.PAGE, Map.of("pageNumber", 1)),
                        new ParseSegment("parser two", 1, SegmentType.PAGE, Map.of("pageNumber", 2))
                ))
                .enhancedSegments(List.of(
                        new ParseSegment("enhanced one", 0, SegmentType.FULL, Map.of()),
                        new ParseSegment("enhanced two", 1, SegmentType.FULL, Map.of())
                ))
                .build();

        String prefix = context.resolveDocumentPrefix(18);

        assertThat(prefix).isEqualTo("enhanced one\n\nenha");
    }
}
