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

    @Test
    void shouldResolveNaturalEnglishOrdinalSelection() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "Operations Alpha"),
                node("2", "Operations Beta")
        );

        IntentNodeDTO resolved = clarificationResolver.resolve("Let's go with the second option.", candidates);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("2");
    }

    @Test
    void shouldResolveNaturalChineseOrdinalSelection() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "运营 Alpha"),
                node("2", "运营 Beta"),
                node("3", "运营 Gamma")
        );

        assertThat(clarificationResolver.resolve("就第一个吧", candidates))
                .extracting(IntentNodeDTO::getId)
                .isEqualTo("1");
        assertThat(clarificationResolver.resolve("第二项比较像", candidates))
                .extracting(IntentNodeDTO::getId)
                .isEqualTo("2");
        assertThat(clarificationResolver.resolve("我选三号", candidates))
                .extracting(IntentNodeDTO::getId)
                .isEqualTo("3");
    }

    @Test
    void shouldReturnNullForOutOfRangeOrdinal() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "Operations Alpha"),
                node("2", "Operations Beta")
        );

        IntentNodeDTO resolved = clarificationResolver.resolve("maybe the third one", candidates);

        assertThat(resolved).isNull();
    }

    @Test
    void shouldNotTreatQuarterReferenceAsOrdinalSelection() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "预算审批"),
                node("2", "采购申请")
        );

        IntentNodeDTO resolved = clarificationResolver.resolve("第一季度的预算还要确认", candidates);

        assertThat(resolved).isNull();
    }

    @Test
    void shouldReturnNullForUnclearClarificationReply() {
        List<IntentNodeDTO> candidates = List.of(
                node("1", "Operations Alpha"),
                node("2", "Operations Beta")
        );

        IntentNodeDTO resolved = clarificationResolver.resolve("I'm not sure yet.", candidates);

        assertThat(resolved).isNull();
    }

    private IntentNodeDTO node(String id, String name) {
        return IntentNodeDTO.builder()
                .id(id)
                .name(name)
                .build();
    }
}
