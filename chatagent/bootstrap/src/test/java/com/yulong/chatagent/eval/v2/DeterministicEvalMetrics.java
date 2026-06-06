package com.yulong.chatagent.eval.v2;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DeterministicEvalMetrics {

    private DeterministicEvalMetrics() {
    }

    public static double hitAtK(List<String> retrieved, Set<String> relevant, int k) {
        return topK(retrieved, k).stream().anyMatch(relevant::contains) ? 1.0 : 0.0;
    }

    public static double recallAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        return intersectionCount(topK(retrieved, k), relevant) / (double) relevant.size();
    }

    public static double precisionAtK(List<String> retrieved, Set<String> relevant, int k) {
        return intersectionCount(topK(retrieved, k), relevant) / (double) k;
    }

    public static double reciprocalRank(List<String> retrieved, Set<String> relevant) {
        for (int index = 0; index < retrieved.size(); index++) {
            if (relevant.contains(retrieved.get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

    public static double ndcgAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        List<String> candidates = topK(retrieved, k);
        double dcg = 0.0;
        for (int index = 0; index < candidates.size(); index++) {
            if (relevant.contains(candidates.get(index))) {
                dcg += 1.0 / log2(index + 2);
            }
        }
        double idealDcg = 0.0;
        for (int index = 0; index < Math.min(relevant.size(), k); index++) {
            idealDcg += 1.0 / log2(index + 2);
        }
        return idealDcg == 0.0 ? 0.0 : dcg / idealDcg;
    }

    public static double phraseRecall(List<String> texts, List<String> requiredPhrases) {
        if (requiredPhrases.isEmpty()) {
            return 0.0;
        }
        String searchable = normalize(String.join(" ", texts));
        long found = requiredPhrases.stream()
                .map(DeterministicEvalMetrics::normalize)
                .filter(searchable::contains)
                .count();
        return found / (double) requiredPhrases.size();
    }

    private static List<String> topK(List<String> retrieved, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        return retrieved.subList(0, Math.min(k, retrieved.size()));
    }

    private static int intersectionCount(List<String> retrieved, Set<String> relevant) {
        Set<String> unique = new HashSet<>(retrieved);
        unique.retainAll(relevant);
        return unique.size();
    }

    private static double log2(int value) {
        return Math.log(value) / Math.log(2);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
