package com.yulong.chatagent.eval.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EvalThresholdEvaluator {

    private EvalThresholdEvaluator() {
    }

    public static Evaluation evaluate(Map<String, Double> metrics, Map<String, Rule> rules) {
        String overall = "pass";
        List<Result> results = new ArrayList<>();
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            String metric = entry.getKey();
            Rule rule = entry.getValue();
            Double value = metrics.get(metric);
            boolean passed = value != null
                    && (rule.min() == null || value >= rule.min())
                    && (rule.max() == null || value <= rule.max());
            String status = passed ? "pass" : rule.severity().value;
            results.add(new Result(metric, value, rule.min(), rule.max(), rule.severity().value, status));
            if ("fail".equals(status)) {
                overall = "fail";
            } else if ("warn".equals(status) && "pass".equals(overall)) {
                overall = "warn";
            }
        }
        return new Evaluation(overall, List.copyOf(results));
    }

    public enum Severity {
        FAIL("fail"),
        WARN("warn");

        private final String value;

        Severity(String value) {
            this.value = value;
        }
    }

    public record Rule(Double min, Double max, Severity severity) {
    }

    public record Result(String metric, Double value, Double min, Double max, String severity, String status) {
    }

    public record Evaluation(String status, List<Result> results) {
    }
}
