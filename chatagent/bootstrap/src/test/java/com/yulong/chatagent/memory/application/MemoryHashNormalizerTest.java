package com.yulong.chatagent.memory.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryHashNormalizerTest {

    @Test
    void shouldNormalizeWhitespace() {
        String result = MemoryHashNormalizer.normalize("  hello   world  \n\t  test  ");
        assertThat(result).isEqualTo("hello world test");
    }

    @Test
    void shouldLowercaseEnglishLetters() {
        String result = MemoryHashNormalizer.normalize("User Prefers ChatAgent");
        assertThat(result).isEqualTo("user prefers chatagent");
    }

    @Test
    void shouldPreserveNonAscii() {
        String result = MemoryHashNormalizer.normalize("用户倾向使用 Milvus");
        assertThat(result).isEqualTo("用户倾向使用 milvus");
    }

    @Test
    void shouldHandleEmptyAndNull() {
        assertThat(MemoryHashNormalizer.normalize("")).isEqualTo("");
        assertThat(MemoryHashNormalizer.normalize(null)).isEqualTo("");
    }

    @Test
    void shouldProduceDeterministicHash() {
        String h1 = MemoryHashNormalizer.hash("user-1", "preference", "likes concise answers");
        String h2 = MemoryHashNormalizer.hash("user-1", "preference", "likes concise answers");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
    }

    @Test
    void shouldProduceDifferentHashForDifferentInputs() {
        String h1 = MemoryHashNormalizer.hash("user-1", "preference", "likes concise answers");
        String h2 = MemoryHashNormalizer.hash("user-1", "fact", "likes concise answers");
        String h3 = MemoryHashNormalizer.hash("user-2", "preference", "likes concise answers");
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
    }

    @Test
    void shouldNormalizeBeforeHashing() {
        // Content that differs only in whitespace/case should produce the same hash
        // when the caller normalizes first.
        String n1 = MemoryHashNormalizer.normalize("User Likes  Concise\nAnswers");
        String n2 = MemoryHashNormalizer.normalize("user likes concise answers");
        assertThat(n1).isEqualTo(n2);
        String h1 = MemoryHashNormalizer.hash("user-1", "preference", n1);
        String h2 = MemoryHashNormalizer.hash("user-1", "preference", n2);
        assertThat(h1).isEqualTo(h2);
    }
}
