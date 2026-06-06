package com.yulong.chatagent.eval.v2;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalThresholdEvaluatorTest {

    @Test
    void failDominatesWarn() {
        Map<String, EvalThresholdEvaluator.Rule> rules = new LinkedHashMap<>();
        rules.put("recall", new EvalThresholdEvaluator.Rule(0.8, null, EvalThresholdEvaluator.Severity.FAIL));
        rules.put("latency", new EvalThresholdEvaluator.Rule(null, 3000.0, EvalThresholdEvaluator.Severity.WARN));

        EvalThresholdEvaluator.Evaluation evaluation = EvalThresholdEvaluator.evaluate(
                Map.of("recall", 0.7, "latency", 3500.0),
                rules
        );

        assertThat(evaluation.status()).isEqualTo("fail");
        assertThat(evaluation.results()).extracting(EvalThresholdEvaluator.Result::status)
                .containsExactly("fail", "warn");
    }
}
