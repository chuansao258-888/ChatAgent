package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.chat.ChatModelRouter;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 意图路由器。
 * <p>
 * 它负责在“当前 agent 的 active intent tree snapshot”上，为一条用户 query 找到最合适的意图路径。
 * 当前实现是分层路由：
 * <ul>
 *     <li>先在当前层候选节点里做启发式打分；</li>
 *     <li>必要时再调用 LLM 分类器做更精确判断；</li>
 *     <li>如果仍然不够确定，就返回 clarification candidates 给上层做澄清。</li>
 * </ul>
 * 所以它不是一个“只会返回命中节点”的路由器，而是可能返回：
 * <ul>
 *     <li>resolved</li>
 *     <li>clarification</li>
 *     <li>none</li>
 * </ul>
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
    private final MeterRegistry meterRegistry;

    public IntentRouter(PromptLoader promptLoader,
                        IntentTreeCacheManager intentTreeCacheManager,
                        ChatModelRouter chatModelRouter,
                        @Value("${chatagent.intent.minimum-score:0.45}") double minimumScore,
                        @Value("${chatagent.intent.ambiguity-gap:0.2}") double ambiguityGap,
                        @Value("${chatagent.intent.clarification-candidates:2}") int clarificationCandidateCount,
                        @Value("${chatagent.intent.classifier-model:}") String classifierModel,
                        ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.promptLoader = promptLoader;
        this.intentTreeCacheManager = intentTreeCacheManager;
        this.chatModelRouter = chatModelRouter;
        this.minimumScore = minimumScore;
        this.ambiguityGap = ambiguityGap;
        this.clarificationCandidateCount = Math.max(clarificationCandidateCount, 2);
        this.classifierModel = classifierModel;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * 常规首轮路由入口。
     * <p>
     * 这里没有 selectedNodeId，表示系统还没有任何“用户已经从澄清候选里选中过某个节点”的上下文，
     * 因此会从根节点开始做完整路由。
     */
    public IntentRoutingResult route(String agentId, String query) {
        return route(agentId, query, null);
    }

    // routeWithHistory 是旧的多轮意图评测辅助入口，生产 turn 准备主链并不调用。
    // 相关历史回退逻辑已经移到 MultiturnDialogueEvalTest 本地，避免生产 IntentRouter
    // 为 eval 保留额外公共 API。

    /**
     * 分层意图路由主入口。
     * <p>
     * 这个方法既处理普通首轮问题，也处理澄清后的 continuation：
     * <ul>
     *     <li>{@code selectedNodeId == null}：说明这是普通首轮路由，从根节点开始；</li>
     *     <li>{@code selectedNodeId != null}：说明用户已经在上一轮澄清里选中过某个节点，
     *     这一轮要从该节点继续向下钻。</li>
     * </ul>
     * 返回值不是“命中节点”这么简单，而是三态：
     * <ul>
     *     <li>resolved：已经可以得到明确意图边界；</li>
     *     <li>clarification：当前层还不够确定，需要上层先问用户一句；</li>
     *     <li>none：没有稳定命中，让上层决定是否直接透传给 Agent。</li>
     * </ul>
     */
    public IntentRoutingResult route(String agentId, String query, String selectedNodeId) {
        long startMs = System.currentTimeMillis();
        IntentRoutingResult result;
        try {
            IntentTreeSnapshot snapshot = intentTreeCacheManager.loadActiveSnapshot(agentId);
            if (snapshot.isEmpty()) {
                // 没有 active snapshot 时，这一轮无法做意图边界控制，直接返回 none。
                result = IntentRoutingResult.none();
                return result;
            }

            // 第一层兜底：如果 query 太短太泛，又不是在 continuation 场景下，
            // 先确认它和根节点是否有关系。树外短词（如“天气”）不应被强行拉进业务树澄清。
            if (isVagueQuery(query) && !StringUtils.hasText(selectedNodeId)) {
                List<IntentNodeDTO> rootCandidates = snapshot.rootNodes();
                if (!rootCandidates.isEmpty()) {
                    RankedSelection rootSelection = select(query, rootCandidates, "");
                    if (rootSelection.noneMatched()
                            || (rootSelection.best() == null && !rootSelection.ambiguous())) {
                        result = IntentRoutingResult.none();
                        return result;
                    }
                    result = IntentRoutingResult.clarification(
                            clarificationCandidatesForVagueRoot(rootSelection, rootCandidates),
                            ""
                    );
                    return result;
                }
            }

            List<IntentNodeDTO> path = new ArrayList<>();
            IntentNodeDTO current = null;
            if (StringUtils.hasText(selectedNodeId)) {
                // selectedNodeId 不为空时，说明这不是普通首轮路由，
                // 而是“用户已经在 clarification 里选中了某个候选节点”，现在从这个节点继续往下钻。
                current = snapshot.findNode(selectedNodeId);
                if (current == null) {
                    result = IntentRoutingResult.none();
                    return result;
                }
                path.addAll(snapshot.pathTo(selectedNodeId));
            }

            while (true) {
                // current 为 null 时，从根节点开始选；
                // 否则就在 current 的 children 里继续向下选。
                List<IntentNodeDTO> candidates = current == null ? snapshot.rootNodes() : snapshot.childrenOf(current.getId());
                if (candidates.isEmpty()) {
                    // 当前层没有更多候选了：
                    // - 如果 current 为 null，说明整棵树都没找到合适节点；
                    // - 否则说明当前 path 已经是最终结果。
                    result = current == null ? IntentRoutingResult.none() : IntentRoutingResult.resolved(buildResolution(snapshot, path));
                    return result;
                }

                if (candidates.size() == 1 && shouldAutoDrill(snapshot, candidates.get(0))) {
                    // 某一层如果只有一个中间节点，而且它下面还有孩子，
                    // 就自动向下钻，不强迫用户为“唯一候选”做无意义选择。
                    current = candidates.get(0);
                    if (path.isEmpty() || !current.getId().equals(path.get(path.size() - 1).getId())) {
                        path.add(current);
                    }
                    continue;
                }

                // select() 只回答“当前这一层怎么选”，还没有到 prepare 层的 direct / dispatch 决策。
                RankedSelection selection = select(query, candidates, buildPathLabel(path));

                if (selection.noneMatched()) {
                    // 如果 LLM/启发式明确判断“这些候选都不对”，而当前节点本身已经是可落地意图，
                    // 则允许停在当前节点；如果当前节点还有子层，则继续向用户澄清下一层候选。
                    // 只有根层没有合适候选时才返回 none。
                    if (current != null && current.getIntentKind() != null) {
                        result = IntentRoutingResult.resolved(buildResolution(snapshot, path));
                        return result;
                    }
                    if (current != null) {
                        result = IntentRoutingResult.clarification(topCandidates(candidates), buildPathLabel(path));
                        return result;
                    }
                    result = IntentRoutingResult.none();
                    return result;
                }

                if (selection.best() == null) {
                    // best 为空但不是 noneMatched，说明“目前不够确定，值得问用户一句”。
                    if (current != null && current.getIntentKind() != null) {
                        result = IntentRoutingResult.resolved(buildResolution(snapshot, path));
                        return result;
                    }
                    // 这里用当前层 candidates，而不是 path 上所有节点。
                    // 因为澄清问题只应该让用户在“此时此层可选项”里做决定。
                    result = IntentRoutingResult.clarification(topCandidates(candidates), buildPathLabel(path));
                    return result;
                }
                if (selection.ambiguous()) {
                    // ambiguous 表示 top1 和 top2 太接近，需要把多个候选返回给上层做 clarification。
                    result = IntentRoutingResult.clarification(selection.topCandidates(), buildPathLabel(path));
                    return result;
                }

                current = selection.best().node();
                if (path.isEmpty() || !current.getId().equals(path.get(path.size() - 1).getId())) {
                    path.add(current);
                }

                if (current.getIntentKind() != null || snapshot.childrenOf(current.getId()).isEmpty()) {
                    // 当前节点已经是叶子意图，或者虽然没显式标 kind 但已经没有子节点了，
                    // 都可以把当前 path 组装成最终 resolution。
                    result = IntentRoutingResult.resolved(buildResolution(snapshot, path));
                    return result;
                }
            }
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            recordTimer("chatagent.intent.routing.latency", durationMs, "agent", agentId);
        }
    }

    private IntentResolution buildResolution(IntentTreeSnapshot snapshot, List<IntentNodeDTO> path) {
        // path 的最后一个节点视为最终落点。
        // 上层后续拿到的不只是一个意图 kind，
        // 还包括该叶子节点约束出来的 KB 范围、工具范围和 prompt override。
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
        // 先做启发式排序，后面无论是直接命中、还是交给 LLM、还是选 topCandidates 做澄清，
        // 都基于这份排序结果。
        List<ScoredNode> scoredNodes = candidates.stream()
                .map(node -> new ScoredNode(node, score(query, node)))
                .sorted(Comparator
                        .comparingDouble(ScoredNode::score).reversed()
                        .thenComparing(scored -> scored.node().getSortOrder() == null ? 0 : scored.node().getSortOrder()))
                .toList();

        if (scoredNodes.isEmpty()) {
            // 当前层没有任何候选可供评分，交给外层走“best 为空”的兜底逻辑即可。
            return new RankedSelection(null, false, false, List.of());
        }

        ScoredNode best = scoredNodes.get(0);
        ScoredNode second = scoredNodes.size() > 1 ? scoredNodes.get(1) : null;

        // 启发式足够强时直接接受，不必每层都走 LLM 分类。
        // 条件是：best 分数非常高，且明显领先第二名。
        if (best.score() >= 1.2d && (second == null || (best.score() - second.score()) > 0.5d)) {
            return new RankedSelection(best, false, false, List.of());
        }

        // 启发式不够稳时，再让 LLM 在候选范围已被缩小的前提下做精细语义判断。
        try {
            String llmResult = callLlmClassifier(candidates, query, pathLabel);
            if ("NONE".equals(llmResult)) {
                // NONE 不是“最佳候选为空”，而是“这些候选都不像”，属于 noneMatched 场景。
                return new RankedSelection(null, false, true, topCandidates(candidates));
            }
            if ("AMBIGUOUS".equals(llmResult)) {
                // AMBIGUOUS 表示 LLM 也觉得模糊，此时返回多个候选交给上层澄清。
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
            // 分数太低时，不贸然命中。
            // 这里故意不直接返回 noneMatched，因为“低置信度”与“明确都不匹配”不是一回事：
            // 前者更适合让上层先问一句澄清。
            return new RankedSelection(null, false, false, topCandidates(candidates));
        }

        // 如果第一名和第二名都过线，且差距不大，就视为模糊。
        boolean ambiguous = second != null
                && second.score() >= minimumScore
                && (best.score() - second.score()) <= ambiguityGap;

        List<IntentNodeDTO> clarificationCandidates = new ArrayList<>();
        // clarificationCandidates 只保留前 N 个高分候选，避免一次给用户太多选项。
        for (int i = 0; i < Math.min(clarificationCandidateCount, scoredNodes.size()); i++) {
            if (scoredNodes.get(i).score() >= minimumScore) {
                clarificationCandidates.add(scoredNodes.get(i).node());
            }
        }
        // 注意：即使 ambiguous=false，也把高分 clarificationCandidates 一并算出来。
        // 这样如果后续上层想记录调试信息或扩展策略，不需要重新做一次候选截取。
        return new RankedSelection(best, ambiguous, false, clarificationCandidates);
    }

    private boolean shouldAutoDrill(IntentTreeSnapshot snapshot, IntentNodeDTO candidate) {
        // 只有一个中间节点时，自动下钻；如果它本身已经是叶子意图，则不走这里。
        if (candidate == null || candidate.getIntentKind() != null) {
            return false;
        }
        return !snapshot.childrenOf(candidate.getId()).isEmpty();
    }

    /**
     * 在启发式已经缩小候选集之后，再调用分类模型做精细选择。
     * 这样既降低 prompt 复杂度，也避免对整棵树做高成本分类。
     */
    private String callLlmClassifier(List<IntentNodeDTO> candidates, String query, String pathLabel) {
        String candidatesText = candidates.stream()
                .map(this::formatCandidateForClassifier)
                .collect(Collectors.joining("\n"));

        String prompt = promptLoader.render(PromptConstants.INTENT_CLASSIFIER, Map.of(
                "pathLevel", pathLabel == null || pathLabel.isBlank() ? "ROOT" : pathLabel,
                "userInput", query,
                "candidatesText", candidatesText
        ));

        // classifierModel 允许独立于主对话模型单独配置，使“意图分类”和“最终回答”可以解耦。
        ChatClient chatClient = chatModelRouter.route(classifierModel);
        String content = chatClient.prompt(prompt)
                .call()
                .content();
        if (!StringUtils.hasText(content)) {
            // 空返回不直接抛错，而是交给外层继续走启发式兜底。
            log.warn("Intent classifier returned blank content: path={}, candidateCount={}",
                    pathLabel,
                    candidates.size());
            return "";
        }
        return content.trim();
    }

    private String formatCandidateForClassifier(IntentNodeDTO node) {
        String description = StringUtils.hasText(node.getDescription()) ? node.getDescription() : "None";
        String kind = node.getIntentKind() == null ? "None" : node.getIntentKind().name();
        String allowedTools = node.getAllowedTools() == null || node.getAllowedTools().isEmpty()
                ? "None"
                : String.join(", ", node.getAllowedTools());
        String examples = node.getExamples() == null || node.getExamples().isEmpty()
                ? "None"
                : String.join(", ", node.getExamples());
        return "- ID: " + node.getId()
                + ", Name: " + node.getName()
                + ", Description: " + description
                + "\n  Kind: " + kind
                + "\n  AllowedTools: " + allowedTools
                + "\n  Examples: " + examples;
    }

    private double score(String query, IntentNodeDTO node) {
        // 当前启发式分数主要来自：
        // 1. query 与 name 的包含/重叠；
        // 2. query 与 description 的重叠；
        // 3. query 与 examples 的最佳匹配。
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
        // 用一个很轻量的字符/词片段交集比，避免过于复杂的本地 NLP 逻辑。
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
        // 这里同时保留单词和双字切片：
        // 对中文 query 更友好，对短文本的重叠感知也更稳。
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

    /**
     * 判断 query 是否“短到不足以稳定路由”。
     * <p>
     * 例如“制度”“流程”“申请”这种 1~2 个中文词的输入，
     * 更适合作为 clarification 入口，而不是硬匹配到某个意图节点。
     */
    private boolean isVagueQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return true;
        }
        String normalized = normalize(query);
        String compact = normalized.replaceAll("\\s+", "");
        if (compact.length() <= 2 && !compact.matches(".*[a-zA-Z].*")) {
            return true;
        }
        return normalized.matches("[a-zA-Z]{1,12}");
    }

    private String normalize(String value) {
        // 规范化主要服务于本地启发式匹配，把大小写、标点和多空格先抹平。
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
        // topCandidates 是 clarification 给用户看的候选列表，不一定等于所有候选。
        // 这里按 sortOrder 排，而不是按启发式分数排，主要是让产品侧能稳定控制展示顺序。
        return candidates.stream()
                .sorted(Comparator.comparing(node -> node.getSortOrder() == null ? 0 : node.getSortOrder()))
                .limit(clarificationCandidateCount)
                .toList();
    }

    private List<IntentNodeDTO> clarificationCandidatesForVagueRoot(RankedSelection selection, List<IntentNodeDTO> rootCandidates) {
        if (selection.topCandidates() != null && !selection.topCandidates().isEmpty()) {
            return selection.topCandidates();
        }
        List<IntentNodeDTO> result = new ArrayList<>();
        if (selection.best() != null && selection.best().node() != null) {
            result.add(selection.best().node());
        }
        for (IntentNodeDTO candidate : topCandidates(rootCandidates)) {
            if (candidate != null && result.stream().noneMatch(existing -> candidate.getId().equals(existing.getId()))) {
                result.add(candidate);
            }
            if (result.size() >= clarificationCandidateCount) {
                break;
            }
        }
        return result;
    }

    private String buildPathLabel(List<IntentNodeDTO> path) {
        // pathLabel 主要用于：
        // 1. 给 LLM classifier 提供当前层级背景；
        // 2. 给 clarification prompt 显示“当前范围”。
        return path.stream()
                .map(IntentNodeDTO::getName)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " > " + right)
                .orElse("");
    }

    private record ScoredNode(IntentNodeDTO node, double score) {
    }

    /**
     * select() 的内部输出。
     * <p>
     * 它比最终的 IntentRoutingResult 更底层，表达的是“在当前层候选里怎么选”的结果：
     * best / ambiguous / noneMatched / topCandidates。
     */
    private record RankedSelection(ScoredNode best, boolean ambiguous, boolean noneMatched, List<IntentNodeDTO> topCandidates) {
    }

    private void recordTimer(String name, long durationMs, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.timer(name, tags).record(Math.max(durationMs, 0L), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to record intent routing timer: name={}, error={}", name, e.getMessage());
        }
    }
}
