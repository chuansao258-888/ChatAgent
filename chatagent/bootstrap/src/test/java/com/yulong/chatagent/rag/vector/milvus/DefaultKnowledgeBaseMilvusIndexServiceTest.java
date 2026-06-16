package com.yulong.chatagent.rag.vector.milvus;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultKnowledgeBaseMilvusIndexServiceTest {

    @Test
    void bm25TextSanitizerRemovesReplacementCharactersAndUnpairedSurrogates() {
        String text = "## Contents What\uFFFD\uFFFDs New \uD800 items";

        assertThat(DefaultKnowledgeBaseMilvusIndexService.sanitizeBm25Text(text))
                .isEqualTo("## Contents What s New items");
    }

    @Test
    void bm25TextSanitizerPreservesValidUnicodeAndCollapsesWhitespace() {
        String text = "  stable\n中文 \uD83D\uDE80  query  ";

        assertThat(DefaultKnowledgeBaseMilvusIndexService.sanitizeBm25Text(text))
                .isEqualTo("stable 中文 \uD83D\uDE80 query");
    }

    @Test
    void milvusTextSanitizerPreservesLayoutWhileRemovingInvalidCodePoints() {
        String text = "first\nWhat\uFFFD\uFFFDs New \uD800 section";

        assertThat(DefaultKnowledgeBaseMilvusIndexService.sanitizeMilvusText(text, 65_535, false))
                .isEqualTo("first\nWhat  s New   section");
    }

    @Test
    void milvusTextSanitizerTruncatesAtUtf8ByteBoundary() {
        String sanitized = DefaultKnowledgeBaseMilvusIndexService.sanitizeMilvusText("ab中文cd", 5, false);

        assertThat(sanitized).isEqualTo("ab中");
        assertThat(sanitized.getBytes(StandardCharsets.UTF_8)).hasSize(5);
    }
}
