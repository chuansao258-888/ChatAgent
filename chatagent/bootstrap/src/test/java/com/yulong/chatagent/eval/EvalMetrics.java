package com.yulong.chatagent.eval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static utility methods for computing information-retrieval evaluation metrics.
 */
public final class EvalMetrics {

    private EvalMetrics() {}

    /**
     * Hit@K: fraction of queries where at least one relevant item appears in the top-K results.
     */
    public static double hitAtK(List<String> rankedDocIds, Set<String> relevantDocIds, int k) {
        if (relevantDocIds.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, rankedDocIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantDocIds.contains(rankedDocIds.get(i))) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * Mean Reciprocal Rank: 1 / rank of the first relevant document.
     * Returns 0 if no relevant document is found.
     */
    public static double mrr(List<String> rankedDocIds, Set<String> relevantDocIds) {
        for (int i = 0; i < rankedDocIds.size(); i++) {
            if (relevantDocIds.contains(rankedDocIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * NDCG@K: Normalized Discounted Cumulative Gain at position K.
     *
     * @param rankedDocIds   the document IDs in ranked order
     * @param relevanceGrades map of documentId to relevance grade (0-3)
     * @param k              the cutoff position
     */
    public static double ndcgAtK(List<String> rankedDocIds, Map<String, Integer> relevanceGrades, int k) {
        double dcg = dcgAtK(rankedDocIds, relevanceGrades, k);
        double idcg = idealDcg(relevanceGrades, k);
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /**
     * F1 score from precision and recall.
     */
    public static double f1(double precision, double recall) {
        if (precision + recall == 0.0) {
            return 0.0;
        }
        return 2.0 * precision * recall / (precision + recall);
    }

    /**
     * Simple accuracy: correct / total.
     */
    public static double accuracy(long correct, long total) {
        return total == 0 ? 0.0 : (double) correct / total;
    }

    private static double dcgAtK(List<String> rankedDocIds, Map<String, Integer> grades, int k) {
        double dcg = 0.0;
        int limit = Math.min(k, rankedDocIds.size());
        for (int i = 0; i < limit; i++) {
            int grade = grades.getOrDefault(rankedDocIds.get(i), 0);
            dcg += (Math.pow(2, grade) - 1) / log2(i + 2);
        }
        return dcg;
    }

    private static double idealDcg(Map<String, Integer> grades, int k) {
        List<Integer> sortedGrades = grades.values().stream()
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
        double idcg = 0.0;
        int limit = Math.min(k, sortedGrades.size());
        for (int i = 0; i < limit; i++) {
            idcg += (Math.pow(2, sortedGrades.get(i)) - 1) / log2(i + 2);
        }
        return idcg;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
