package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Heuristic hierarchical router for the active intent tree snapshot,
 * with LLM fallback for deep semantic classification.
 */
@Component
@Slf4j
public class IntentRouter {

    private final PromptLoader promptLoader;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final ChatModelRouter chatModelRouter;
    private final double minimumScore;
    private final double ambiguityGap;
    private final int clarificationCandidateCount;
    private final String classifierModel;

    public IntentRouter(PromptLoader promptLoader,
                        IntentTreeCacheManager intentTreeCacheManager,
                        ChatModelRouter chatModelRouter,
                        @Value("${chatagent.intent.minimum-score:0.45}") double minimumScore,
                        @Value("${chatagent.intent.ambiguity-gap:0.2}") double ambiguityGap,
                        @Value("${chatagent.intent.clarification-candidates:2}") int clarificationCandidateCount,
                        @Value("${chatagent.intent.classifier-model:}") String classifierModel) {
        this.promptLoader = promptLoader;
        this.intentTreeCacheManager = intentTreeCacheManager;
        this.chatModelRouter = chatModelRouter;
        this.minimumScore = minimumScore;
        this.ambiguityGap = ambiguityGap;
        this.clarificationCandidateCount = Math.max(clarificationCandidateCount, 2);
        this.classifierModel = classifierModel;
    }

    public IntentRoutingResult route(String agentId, String query) {
        return route(agentId, query, null);
    }

    public IntentRoutingResult route(String agentId, String query, String selectedNodeId) {
        IntentTreeSnapshot snapshot = intentTreeCacheManager.loadActiveSnapshot(agentId);
        if (snapshot.isEmpty()) {
            return IntentRoutingResult.none();
        }

        List<IntentNodeDTO> path = new ArrayList<>();
        IntentNodeDTO current = null;
        if (StringUtils.hasText(selectedNodeId)) {
            current = snapshot.findNode(selectedNodeId);
            if (current == null) {
                return IntentRoutingResult.none();
            }
            path.addAll(snapshot.pathTo(selectedNodeId));
        }

        while (true) {
            List<IntentNodeDTO> candidates = current == null ? snapshot.rootNodes() : snapshot.childrenOf(current.getId());
            if (candidates.isEmpty()) {
                return current == null ? IntentRoutingResult.none() : IntentRoutingResult.resolved(buildResolution(snapshot, path));
            }

            if (candidates.size() == 1 && shouldAutoDrill(snapshot, candidates.get(0))) {
                current = candidates.get(0);
                if (path.isEmpty() || !current.getId().equals(path.get(path.size() - 1).getId())) {
                    path.add(current);
                }
                continue;
            }

            RankedSelection selection = select(query, candidates, buildPathLabel(path));

            if (selection.noneMatched()) {
                if (current != null && current.getIntentKind() != null) {
                    return IntentRoutingResult.resolved(buildResolution(snapshot, path));
                }
                return IntentRoutingResult.none();
            }

            if (selection.best() == null) {
                if (current != null && current.getIntentKind() != null) {
                    return IntentRoutingResult.resolved(buildResolution(snapshot, path));
                }
                return IntentRoutingResult.clarification(topCandidates(candidates), buildPathLabel(path));
            }
            if (selection.ambiguous()) {
                return IntentRoutingResult.clarification(selection.topCandidates(), buildPathLabel(path));
            }

            current = selection.best().node();
            if (path.isEmpty() || !current.getId().equals(path.get(path.size() - 1).getId())) {
                path.add(current);
            }

            if (current.getIntentKind() != null || snapshot.childrenOf(current.getId()).isEmpty()) {
                return IntentRoutingResult.resolved(buildResolution(snapshot, path));
            }
        }
    }

    private IntentResolution buildResolution(IntentTreeSnapshot snapshot, List<IntentNodeDTO> path) {
        IntentNodeDTO leaf = path.get(path.size() - 1);
        IntentKind kind = leaf.getIntentKind() == null ? IntentKind.KB : leaf.getIntentKind();
        ScopePolicy scopePolicy = leaf.getScopePolicy();
        if (scopePolicy == null && kind == IntentKind.KB) {
            scopePolicy = ScopePolicy.FALLBACK_ALLOWED;
        }
        if (scopePolicy == null) {
            scopePolicy = ScopePolicy.STRICT;
        }
        return new IntentResolution(
                kind,
                path,
                snapshot.knowledgeBaseIdsForNode(leaf.getId()),
                scopePolicy,
                leaf.getAllowedTools(),
                leaf.getSystemPromptOverride()
        );
    }

    private RankedSelection select(String query, List<IntentNodeDTO> candidates, String pathLabel) {
        List<ScoredNode> scoredNodes = candidates.stream()
                .map(node -> new ScoredNode(node, score(query, node)))
                .sorted(Comparator
                        .comparingDouble(ScoredNode::score).reversed()
                        .thenComparing(scored -> scored.node().getSortOrder() == null ? 0 : scored.node().getSortOrder()))
                .toList();

        if (scoredNodes.isEmpty()) {
            return new RankedSelection(null, false, false, List.of());
        }

        ScoredNode best = scoredNodes.get(0);
        ScoredNode second = scoredNodes.size() > 1 ? scoredNodes.get(1) : null;

        // Use heuristic shortcut ONLY if score is very high AND definitively better than the second best.
        if (best.score() >= 1.2d && (second == null || (best.score() - second.score()) > 0.5d)) {
            return new RankedSelection(best, false, false, List.of());
        }

        // Fall back to LLM for precise semantic matching
        try {
            String llmResult = callLlmClassifier(candidates, query, pathLabel);
            if ("NONE".equals(llmResult)) {
                return new RankedSelection(null, false, true, topCandidates(candidates));
            }
            if ("AMBIGUOUS".equals(llmResult)) {
                return new RankedSelection(null, true, false, topCandidates(candidates));
            }

            for (ScoredNode scored : scoredNodes) {
                if (scored.node().getId().equals(llmResult)) {
                    return new RankedSelection(scored, false, false, List.of());
                }
            }
        } catch (Exception e) {
            log.warn("Intent classification fallback to heuristic: path={}, candidateCount={}, error={}",
                    pathLabel,
                    candidates.size(),
                    e.getMessage());
        }

        if (best.score() < minimumScore) {
            return new RankedSelection(null, false, false, topCandidates(candidates));
        }

        boolean ambiguous = second != null
                && second.score() >= minimumScore
                && (best.score() - second.score()) <= ambiguityGap;

        List<IntentNodeDTO> clarificationCandidates = new ArrayList<>();
        for (int i = 0; i < Math.min(clarificationCandidateCount, scoredNodes.size()); i++) {
            if (scoredNodes.get(i).score() >= minimumScore) {
                clarificationCandidates.add(scoredNodes.get(i).node());
            }
        }
        return new RankedSelection(best, ambiguous, false, clarificationCandidates);
    }

    private boolean shouldAutoDrill(IntentTreeSnapshot snapshot, IntentNodeDTO candidate) {
        if (candidate == null || candidate.getIntentKind() != null) {
            return false;
        }
        return !snapshot.childrenOf(candidate.getId()).isEmpty();
    }

    /**
     * Uses the classifier model only after heuristic scoring has narrowed the candidate list.
     */
    private String callLlmClassifier(List<IntentNodeDTO> candidates, String query, String pathLabel) {
        String candidatesText = candidates.stream()
                .map(n -> "- ID: " + n.getId() + ", Name: " + n.getName() + ", Description: " + (n.getDescription() == null ? "None" : n.getDescription()))
                .collect(Collectors.joining("\n"));

        String prompt = promptLoader.render(PromptConstants.INTENT_CLASSIFIER, Map.of(
                "pathLevel", pathLabel == null || pathLabel.isBlank() ? "ROOT" : pathLabel,
                "userInput", query,
                "candidatesText", candidatesText
        ));

        ChatClient chatClient = chatModelRouter.route(classifierModel);
        String content = chatClient.prompt(prompt)
                .call()
                .content();
        if (!StringUtils.hasText(content)) {
            log.warn("Intent classifier returned blank content: path={}, candidateCount={}",
                    pathLabel,
                    candidates.size());
            return "";
        }
        return content.trim();
    }

    private double score(String query, IntentNodeDTO node) {
        if (!StringUtils.hasText(query) || node == null) {
            return 0.0d;
        }
        String normalizedQuery = normalize(query);
        String normalizedName = normalize(node.getName());
        double score = 0.0d;

        if (StringUtils.hasText(normalizedName)) {
            if (normalizedQuery.contains(normalizedName) || normalizedName.contains(normalizedQuery)) {
                score += 1.2d;
            }
            score += overlapScore(normalizedQuery, normalizedName) * 0.7d;
        }

        if (StringUtils.hasText(node.getDescription())) {
            score += overlapScore(normalizedQuery, normalize(node.getDescription())) * 0.4d;
        }

        double bestExampleScore = 0.0d;
        if (node.getExamples() != null) {
            for (String example : node.getExamples()) {
                String normalizedExample = normalize(example);
                if (!StringUtils.hasText(normalizedExample)) {
                    continue;
                }
                double exampleScore = 0.0d;
                if (normalizedQuery.contains(normalizedExample) || normalizedExample.contains(normalizedQuery)) {
                    exampleScore += 1.0d;
                }
                exampleScore += overlapScore(normalizedQuery, normalizedExample) * 0.6d;
                bestExampleScore = Math.max(bestExampleScore, exampleScore);
            }
        }
        return score + bestExampleScore;
    }

    private double overlapScore(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return 0.0d;
        }
        Set<String> leftUnits = splitUnits(left);
        Set<String> rightUnits = splitUnits(right);
        if (leftUnits.isEmpty() || rightUnits.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new LinkedHashSet<>(leftUnits);
        intersection.retainAll(rightUnits);
        Set<String> union = new LinkedHashSet<>(leftUnits);
        union.addAll(rightUnits);
        return union.isEmpty() ? 0.0d : ((double) intersection.size()) / union.size();
    }

    private Set<String> splitUnits(String text) {
        Set<String> units = new LinkedHashSet<>();
        String compact = text.replace(" ", "");
        if (compact.length() <= 1) {
            if (!compact.isBlank()) {
                units.add(compact);
            }
            return units;
        }
        for (String word : text.split("\\s+")) {
            if (word.length() > 1) {
                units.add(word);
            }
        }
        for (int i = 0; i < compact.length() - 1; i++) {
            units.add(compact.substring(i, i + 2));
        }
        return units;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：、“”‘’（）()\\[\\]{}]+", " ")
                .replaceAll("\\s+", " ");
    }

    private List<IntentNodeDTO> topCandidates(List<IntentNodeDTO> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparing(node -> node.getSortOrder() == null ? 0 : node.getSortOrder()))
                .limit(clarificationCandidateCount)
                .toList();
    }

    private String buildPathLabel(List<IntentNodeDTO> path) {
        return path.stream()
                .map(IntentNodeDTO::getName)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " > " + right)
                .orElse("");
    }

    private record ScoredNode(IntentNodeDTO node, double score) {
    }

    private record RankedSelection(ScoredNode best, boolean ambiguous, boolean noneMatched, List<IntentNodeDTO> topCandidates) {
    }
}
