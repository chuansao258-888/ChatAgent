package com.yulong.chatagent.rag.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocumentParserTest {

    @Test
    void shouldMarkParsedSegmentsAsMarkdown() {
        MarkdownDocumentParser parser = new MarkdownDocumentParser();

        ParseResult result = parser.parse("plain markdown note".getBytes(StandardCharsets.UTF_8), "text/markdown", Map.of());

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("contentFormat", "MARKDOWN")
                .containsEntry("parserType", ParserType.MARKDOWN.getType());
    }
}
