package com.yulong.chatagent.agent.deepthink;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DeepThink 执行过程中的观察收集器。
 * <p>
 * 可变的累加器，记录已完成的步骤、使用的工具和调用计数，
 * 用于构建最终的 {@link com.yulong.chatagent.support.dto.AgentTraceMetadata}。
 */
public class DeepThinkNotebook {

    static final int MAX_EVIDENCE_ENTRIES = 40;
    static final int MAX_EVIDENCE_CHARS = 2_000;

    private final List<DeepThinkPlanStep> completedSteps = new ArrayList<>();
    private final Set<String> toolsUsed = new HashSet<>();
    private final List<DeepThinkEvidenceEntry> evidence = new ArrayList<>();
    private int totalToolCalls;
    private int totalLlmCalls;

    public void recordStepCompletion(DeepThinkPlanStep step, String conclusion) {
        recordStepResult(step, DeepThinkStepResult.completed(conclusion));
    }

    public void recordStepResult(DeepThinkPlanStep step, DeepThinkStepResult result) {
        DeepThinkPlanStep completed = DeepThinkPlanStep.builder()
                .id(step.getId())
                .title(step.getTitle())
                .objective(step.getObjective())
                .status(result.status().name())
                .conclusion(truncate(result.conclusion(), MAX_EVIDENCE_CHARS))
                .build();
        completedSteps.add(completed);
    }

    public void recordStepFailure(DeepThinkPlanStep step) {
        DeepThinkPlanStep failed = DeepThinkPlanStep.builder()
                .id(step.getId())
                .title(step.getTitle())
                .objective(step.getObjective())
                .status("FAILED")
                .build();
        completedSteps.add(failed);
    }

    public void recordToolUsage(String toolName, int callCount) {
        toolsUsed.add(toolName);
        totalToolCalls += callCount;
    }

    public void recordEvidence(String stepId, String toolName, String toolCallId, String content) {
        if (evidence.size() >= MAX_EVIDENCE_ENTRIES) {
            return;
        }
        String safe = content == null ? "" : content;
        boolean truncated = safe.length() > MAX_EVIDENCE_CHARS;
        evidence.add(new DeepThinkEvidenceEntry(
                stepId, toolName, toolCallId, truncate(safe, MAX_EVIDENCE_CHARS), truncated));
    }

    public List<DeepThinkEvidenceEntry> getEvidence() {
        return List.copyOf(evidence);
    }

    public void incrementLlmCalls() {
        totalLlmCalls++;
    }

    public List<DeepThinkPlanStep> getCompletedSteps() {
        return List.copyOf(completedSteps);
    }

    public List<String> getToolsUsed() {
        return List.copyOf(toolsUsed);
    }

    public int getTotalToolCalls() {
        return totalToolCalls;
    }

    public int getTotalLlmCalls() {
        return totalLlmCalls;
    }

    public boolean hasIncompleteSteps() {
        return completedSteps.stream().anyMatch(step ->
                !DeepThinkStepStatus.COMPLETED.name().equals(step.getStatus()));
    }

    /**
     * 构建已收集观察的文本摘要，供最终综合 prompt 使用。
     */
    public String buildObservationsSummary() {
        StringBuilder sb = new StringBuilder();
        for (DeepThinkPlanStep step : completedSteps) {
            sb.append("[").append(step.getId()).append("] ");
            sb.append(step.getTitle());
            if (step.getConclusion() != null) {
                sb.append(" → ").append(step.getConclusion());
            }
            sb.append("\n");
        }
        for (DeepThinkEvidenceEntry entry : evidence) {
            sb.append("[evidence step=").append(entry.stepId())
                    .append(" tool=").append(entry.toolName())
                    .append(" call=").append(entry.toolCallId()).append("] ")
                    .append(entry.content()).append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
