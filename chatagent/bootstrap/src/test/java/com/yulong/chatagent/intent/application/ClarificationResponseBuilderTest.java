package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationResponseBuilderTest {

    private final ClarificationResponseBuilder builder = new ClarificationResponseBuilder();

    @Test
    void shouldRenderEnglishAndDefaultToEnglishWhenLanguageIsUnclear() {
        List<IntentNodeDTO> candidates = List.of(node("Operations Alpha"), node("Operations Beta"));

        assertThat(builder.build(candidates, "", false, "operations"))
                .startsWith("I need to confirm")
                .contains("Please choose:", "1. Operations Alpha", "2. Operations Beta");
        assertThat(builder.build(List.of(), "Operations", true, "2"))
                .startsWith("The previous options are no longer available.")
                .contains("Current scope: Operations");
    }

    @Test
    void shouldRenderChineseForChineseDominantInput() {
        assertThat(builder.build(List.of(node("年假")), "人事", false, "请问年假怎么申请"))
                .startsWith("我需要先确认")
                .contains("当前范围：人事", "请选择：", "1. 年假");
    }

    private IntentNodeDTO node(String name) {
        return IntentNodeDTO.builder().name(name).build();
    }
}
