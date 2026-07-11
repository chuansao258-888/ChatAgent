package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Produces hierarchy-aware lexical evidence with deterministic relevance ordering. */
@Component
public class IntentCandidateGenerator {

    public static final String FEATURE_VERSION = "intent-features-v1";

    private static final Pattern PUNCTUATION = Pattern.compile(
            "[\\p{Punct}，。！？；：、“”‘’（）()\\[\\]【】]+"
    );

    public List<IntentCandidate> generate(IntentTreeSnapshot snapshot, String query) {
        if (snapshot == null || snapshot.isEmpty() || !StringUtils.hasText(query)) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        List<IntentCandidate> ranked = new ArrayList<>();
        int encounterOrder = 0;
        for (IntentNodeDTO node : routableNodes(snapshot)) {
            ScoreEvidence evidence = score(snapshot, node, normalizedQuery);
            ranked.add(new IntentCandidate(
                    node,
                    pathLabel(snapshot, node.getId()),
                    evidence.score(),
                    0.0d,
                    evidence.exactName(),
                    evidence.exactExample(),
                    encounterOrder++,
                    evidence.reasonCodes()
            ));
        }
        ranked.sort(Comparator
                .comparingDouble(IntentCandidate::lexicalScore).reversed()
                .thenComparingInt(candidate -> sortOrder(candidate.node()))
                .thenComparing(candidate -> candidate.node().getId(), Comparator.nullsLast(String::compareTo))
                .thenComparingInt(IntentCandidate::stableOrder));

        List<IntentCandidate> withGaps = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            double next = i + 1 < ranked.size() ? ranked.get(i + 1).lexicalScore() : 0.0d;
            withGaps.add(ranked.get(i).withGap(ranked.get(i).lexicalScore() - next));
        }
        return List.copyOf(withGaps);
    }

    public List<IntentCandidate> limit(List<IntentCandidate> candidates, int maximum) {
        if (candidates == null || candidates.isEmpty() || maximum <= 0) {
            return List.of();
        }
        return List.copyOf(candidates.subList(0, Math.min(candidates.size(), maximum)));
    }

    private List<IntentNodeDTO> routableNodes(IntentTreeSnapshot snapshot) {
        List<IntentNodeDTO> result = new ArrayList<>();
        for (IntentNodeDTO node : snapshot.getNodes()) {
            if (node == null || Boolean.FALSE.equals(node.getEnabled()) || !StringUtils.hasText(node.getId())) {
                continue;
            }
            if (node.getIntentKind() != null || snapshot.childrenOf(node.getId()).isEmpty()) {
                result.add(node);
            }
        }
        return result;
    }

    private ScoreEvidence score(IntentTreeSnapshot snapshot, IntentNodeDTO node, String query) {
        String name = normalize(node.getName());
        String path = normalize(pathLabel(snapshot, node.getId()));
        boolean exactName = StringUtils.hasText(name) && query.equals(name);
        double score = exactName ? 2.0d : 0.0d;
        List<String> reasons = new ArrayList<>();
        if (exactName) {
            reasons.add("exact_name");
        } else if (StringUtils.hasText(name) && (query.contains(name) || name.contains(query))) {
            score += 1.2d;
            reasons.add("name_containment");
        }
        double nameOverlap = Math.max(overlapScore(query, name), coverageScore(query, name));
        score += nameOverlap * 0.7d;
        if (nameOverlap > 0.0d) {
            reasons.add("name_overlap");
        }
        double pathOverlap = overlapScore(query, path);
        score += pathOverlap * 0.35d;
        if (pathOverlap > 0.0d) {
            reasons.add("path_overlap");
        }
        if (StringUtils.hasText(node.getDescription())) {
            score += overlapScore(query, normalize(node.getDescription())) * 0.4d;
        }

        boolean exactExample = false;
        double bestExample = 0.0d;
        if (node.getExamples() != null) {
            for (String example : node.getExamples()) {
                if (!StringUtils.hasText(example)) {
                    continue;
                }
                String normalizedExample = normalize(example);
                if (query.equals(normalizedExample)) {
                    exactExample = true;
                    bestExample = Math.max(bestExample, 1.5d);
                } else if (query.contains(normalizedExample) || normalizedExample.contains(query)) {
                    bestExample = Math.max(bestExample, 1.0d);
                }
                bestExample = Math.max(bestExample, overlapScore(query, normalizedExample) * 0.6d);
            }
        }
        score += bestExample;
        if (exactExample) {
            reasons.add("exact_reviewed_example");
        } else if (bestExample > 0.0d) {
            reasons.add("example_overlap");
        }
        if (reasons.isEmpty()) {
            reasons.add("no_lexical_evidence");
        }
        return new ScoreEvidence(score, exactName, exactExample, List.copyOf(reasons));
    }

    private double overlapScore(String left, String right) {
        Set<String> leftTokens = splitUnits(left);
        Set<String> rightTokens = splitUnits(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private double coverageScore(String query, String candidateText) {
        Set<String> queryUnits = splitUnits(query);
        Set<String> candidateUnits = splitUnits(candidateText);
        if (queryUnits.isEmpty() || candidateUnits.isEmpty()) {
            return 0.0d;
        }
        Set<String> matched = new LinkedHashSet<>(candidateUnits);
        matched.retainAll(queryUnits);
        return (double) matched.size() / candidateUnits.size();
    }

    private Set<String> splitUnits(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        Set<String> units = new LinkedHashSet<>();
        for (String word : text.split("\\s+")) {
            if (word.length() > 1) {
                units.add(word);
            }
        }
        String cjkOnly = text.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i + 2 <= cjkOnly.length(); i++) {
            units.add(cjkOnly.substring(i, i + 2));
        }
        return units;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return PUNCTUATION.matcher(text.trim().toLowerCase(Locale.ROOT))
                .replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private String pathLabel(IntentTreeSnapshot snapshot, String nodeId) {
        return snapshot.pathTo(nodeId).stream()
                .map(IntentNodeDTO::getName)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " > " + right)
                .orElse("");
    }

    private int sortOrder(IntentNodeDTO node) {
        return node.getSortOrder() == null ? 0 : node.getSortOrder();
    }

    private record ScoreEvidence(
            double score,
            boolean exactName,
            boolean exactExample,
            List<String> reasonCodes
    ) {
    }
}
