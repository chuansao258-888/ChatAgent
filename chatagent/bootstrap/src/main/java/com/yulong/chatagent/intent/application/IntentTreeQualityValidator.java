package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Produces a content-free quality and auto-route eligibility report for one tree snapshot. */
@Component
public class IntentTreeQualityValidator {

    public IntentTreeQualityReport validate(IntentTreeSnapshot snapshot, int minimumReviewedExamples) {
        if (snapshot == null || snapshot.isEmpty()) {
            return new IntentTreeQualityReport(List.of(), Set.of());
        }
        int minimumExamples = Math.max(minimumReviewedExamples, 0);
        List<QualityFinding> findings = new ArrayList<>();
        Set<String> eligible = new LinkedHashSet<>();
        for (IntentNodeDTO node : routableNodes(snapshot)) {
            if (StringUtils.hasText(node.getId())) {
                eligible.add(node.getId());
            }
        }
        checkDuplicateSiblingNames(snapshot, findings, eligible);
        checkLeafDescriptions(snapshot, minimumExamples, findings, eligible);
        checkDuplicateExamples(snapshot, findings, eligible);
        return new IntentTreeQualityReport(findings, eligible);
    }

    public IntentTreeQualityReport validate(IntentTreeSnapshot snapshot) {
        return validate(snapshot, 2);
    }

    private void checkDuplicateSiblingNames(IntentTreeSnapshot snapshot,
                                            List<QualityFinding> findings,
                                            Set<String> eligible) {
        for (List<IntentNodeDTO> siblings : groupByParent(snapshot).values()) {
            Map<String, List<String>> idsByName = new HashMap<>();
            for (IntentNodeDTO node : siblings) {
                if (StringUtils.hasText(node.getName()) && StringUtils.hasText(node.getId())) {
                    idsByName.computeIfAbsent(normalize(node.getName()), ignored -> new ArrayList<>())
                            .add(node.getId());
                }
            }
            idsByName.values().stream().filter(ids -> ids.size() > 1).forEach(ids -> {
                eligible.removeAll(ids);
                findings.add(new QualityFinding(Severity.HIGH, "duplicate_sibling_name", ids, ids.size()));
            });
        }
    }

    private void checkLeafDescriptions(IntentTreeSnapshot snapshot,
                                       int minimumExamples,
                                       List<QualityFinding> findings,
                                       Set<String> eligible) {
        for (IntentNodeDTO node : routableNodes(snapshot)) {
            List<String> codes = new ArrayList<>();
            if (!StringUtils.hasText(node.getDescription())) {
                codes.add("blank_leaf_description");
            }
            long examples = node.getExamples() == null ? 0L : node.getExamples().stream()
                    .filter(StringUtils::hasText)
                    .map(this::normalize)
                    .distinct()
                    .count();
            if (examples < minimumExamples) {
                codes.add("insufficient_reviewed_examples");
            }
            for (String code : codes) {
                eligible.remove(node.getId());
                findings.add(new QualityFinding(
                        Severity.MEDIUM, code, List.of(node.getId()), 1));
            }
        }
    }

    private void checkDuplicateExamples(IntentTreeSnapshot snapshot,
                                        List<QualityFinding> findings,
                                        Set<String> eligible) {
        for (List<IntentNodeDTO> siblings : groupByParent(snapshot).values()) {
            Map<String, Set<String>> ownersByExample = new HashMap<>();
            for (IntentNodeDTO node : siblings) {
                if (!StringUtils.hasText(node.getId()) || node.getExamples() == null) {
                    continue;
                }
                for (String example : node.getExamples()) {
                    if (StringUtils.hasText(example)) {
                        ownersByExample.computeIfAbsent(normalize(example), ignored -> new LinkedHashSet<>())
                                .add(node.getId());
                    }
                }
            }
            ownersByExample.values().stream().filter(ids -> ids.size() > 1).forEach(ids -> {
                eligible.removeAll(ids);
                List<String> orderedIds = List.copyOf(ids);
                findings.add(new QualityFinding(Severity.HIGH, "duplicate_sibling_example",
                        orderedIds, orderedIds.size()));
            });
        }
    }

    private List<IntentNodeDTO> routableNodes(IntentTreeSnapshot snapshot) {
        return snapshot.getNodes().stream()
                .filter(node -> node != null && StringUtils.hasText(node.getId()))
                .filter(node -> !Boolean.FALSE.equals(node.getEnabled()))
                .filter(node -> node.getIntentKind() != null || snapshot.childrenOf(node.getId()).isEmpty())
                .toList();
    }

    private Map<String, List<IntentNodeDTO>> groupByParent(IntentTreeSnapshot snapshot) {
        Map<String, List<IntentNodeDTO>> groups = new HashMap<>();
        for (IntentNodeDTO node : snapshot.getNodes()) {
            if (node != null && !Boolean.FALSE.equals(node.getEnabled())) {
                groups.computeIfAbsent(node.getParentId() == null ? "__root__" : node.getParentId(),
                        ignored -> new ArrayList<>()).add(node);
            }
        }
        return groups;
    }

    private String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    public record QualityFinding(
            Severity severity,
            String code,
            List<String> nodeIds,
            int affectedCount
    ) {
        public QualityFinding {
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
            affectedCount = Math.max(affectedCount, 0);
        }
    }

    public record IntentTreeQualityReport(
            List<QualityFinding> findings,
            Set<String> autoRouteEligibleNodeIds
    ) {
        public IntentTreeQualityReport {
            findings = findings == null ? List.of() : List.copyOf(findings);
            autoRouteEligibleNodeIds = autoRouteEligibleNodeIds == null
                    ? Set.of() : Set.copyOf(autoRouteEligibleNodeIds);
        }

        public boolean isAutoRouteEligible(String nodeId) {
            return nodeId != null && autoRouteEligibleNodeIds.contains(nodeId);
        }

        public boolean hasHighSeverity() {
            return findings.stream().anyMatch(finding -> finding.severity() == Severity.HIGH);
        }
    }
}
