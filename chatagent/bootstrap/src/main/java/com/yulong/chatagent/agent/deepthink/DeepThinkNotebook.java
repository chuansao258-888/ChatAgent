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

    private final List<DeepThinkPlanStep> completedSteps = new ArrayList<>();
    private final Set<String> toolsUsed = new HashSet<>();
    private int totalToolCalls;
    private int totalLlmCalls;

    public void recordStepCompletion(DeepThinkPlanStep step, String conclusion) {
        DeepThinkPlanStep completed = DeepThinkPlanStep.builder()
                .id(step.getId())
                .title(step.getTitle())
                .objective(step.getObjective())
                .status("COMPLETED")
                .conclusion(truncate(conclusion, 200))
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
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
