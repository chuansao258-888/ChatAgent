package com.yulong.chatagent.memory.application;

import com.yulong.chatagent.TestPromptLoader;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryPromptPolicyTest {

    private final PromptLoader promptLoader = TestPromptLoader.create();

    @Test
    void extractorShouldClassifyPreferencesByMeaningRatherThanGrammar() {
        String prompt = promptLoader.render("memory/l3-extractor.md", Map.of(
                "formattedTurns",
                "User: My durable review badge preference is DURABLE-BADGE-123.\nAssistant: OK"
        ));

        assertThat(prompt)
                .contains("recurring interaction style, label, format, wording")
                .contains("phrased as a fact about")
                .contains("Classify by semantic meaning, not sentence grammar")
                .contains("preferred review badge is X")
                .contains("MUST be `preference`")
                .contains("project codename is X")
                .contains("MUST be `fact`");
    }
}
