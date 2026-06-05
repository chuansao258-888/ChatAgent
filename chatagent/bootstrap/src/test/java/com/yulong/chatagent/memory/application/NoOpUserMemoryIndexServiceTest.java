package com.yulong.chatagent.memory.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpUserMemoryIndexServiceTest {

    private NoOpUserMemoryIndexService service;

    @BeforeEach
    void setUp() {
        service = new NoOpUserMemoryIndexService();
    }

    @Test
    void shouldReturnEmptySearchHits() {
        List<UserMemorySearchHit> hits = service.search("user-1", new float[]{0.1f, 0.2f}, 3);
        assertThat(hits).isEmpty();
    }

    @Test
    void shouldReturnFalseOnUpsert() {
        boolean result = service.upsertMemory("mem-1", "user-1", "fact", "active",
                "content", "[]", new float[]{0.1f});
        assertThat(result).isFalse();
    }

    @Test
    void shouldNotThrowOnEnsureCollection() {
        assertThatCode(() -> service.ensureCollection()).doesNotThrowAnyException();
    }
}
