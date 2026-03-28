package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationResolverTest {

    private final ClarificationResolver clarificationResolver = new ClarificationResolver();

    @Test
    void shouldResolveHigherOrdinalBeyondTopThree() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "请假制度"),
                node("2", "考勤制度"),
                node("3", "报销制度"),
                node("4", "加班制度")
        );

        IntentNodeDTO resolved = clarificationResolver.resolve("第4个", candidates);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("4");
    }

    @Test
    void shouldResolveByCandidateName() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "请假制度"),
                node("2", "报销制度")
        );

        IntentNodeDTO resolved = clarificationResolver.resolve("我选报销制度", candidates);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("2");
    }

    private IntentNodeDTO node(String id, String name) {
        return IntentNodeDTO.builder()
                .id(id)
                .name(name)
                .build();
    }
}
