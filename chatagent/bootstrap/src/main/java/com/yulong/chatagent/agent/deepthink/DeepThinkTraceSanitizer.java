package com.yulong.chatagent.agent.deepthink;

import com.yulong.chatagent.support.dto.AgentTraceMetadata;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 从计划 + 笔记本构建经过清理的 {@link AgentTraceMetadata}。
 * <p>
 * 遵循计划文档 Section 9.1 的 sanitized trace schema：
 * 字符长度限制、排除原始 tool payload、排除原始 model output。
 */
public class DeepThinkTraceSanitizer {

    private static final int MAX_GOAL_LEN = 200;
    private static final int MAX_TITLE_LEN = 100;
    private static final int MAX_CONCLUSION_LEN = 200;
    private static final int MAX_SUMMARY_LEN = 200;
    private static final int MAX_CLAIM_LEN = 100;

    public static AgentTraceMetadata buildTrace(String mode,
                                                  DeepThinkPlan plan,
                                                  DeepThinkNotebook notebook) {
        return buildTrace(mode, plan, notebook, null, null);
    }

    public static AgentTraceMetadata buildTrace(String mode,
                                                  DeepThinkPlan plan,
                                                  DeepThinkNotebook notebook,
                                                  DeepThinkReflectionResult reflectionResult,
                                                  DeepThinkVerificationResult verificationResult) {
        return AgentTraceMetadata.builder()
                .mode(mode)
                .planning(buildPlanningTrace(plan))
                .execution(buildExecutionTrace(notebook))
                .reflection(buildReflectionTrace(reflectionResult))
                .verification(buildVerificationTrace(verificationResult))
                .build();
    }

    private static AgentTraceMetadata.PlanningTrace buildPlanningTrace(DeepThinkPlan plan) {
        if (plan == null) {
            return null;
        }
        List<AgentTraceMetadata.PlanStepSummary> stepSummaries = plan.getSteps() != null
                ? plan.getSteps().stream()
                .map(step -> AgentTraceMetadata.PlanStepSummary.builder()
                        .id(step.getId())
                        .title(truncate(step.getTitle(), MAX_TITLE_LEN))
                        .status(step.getStatus() != null ? step.getStatus() : "PENDING")
                        .build())
                .collect(Collectors.toList())
                : List.of();

        return AgentTraceMetadata.PlanningTrace.builder()
                .goal(truncate(plan.getGoal(), MAX_GOAL_LEN))
                .stepCount(stepSummaries.size())
                .steps(stepSummaries)
                .build();
    }

    private static AgentTraceMetadata.ExecutionTrace buildExecutionTrace(DeepThinkNotebook notebook) {
        if (notebook == null) {
            return null;
        }
        List<AgentTraceMetadata.StepSummary> stepSummaries = notebook.getCompletedSteps().stream()
                .map(step -> AgentTraceMetadata.StepSummary.builder()
                        .stepId(step.getId())
                        .conclusion(truncate(step.getConclusion(), MAX_CONCLUSION_LEN))
                        .build())
                .collect(Collectors.toList());

        return AgentTraceMetadata.ExecutionTrace.builder()
                .toolsUsed(notebook.getToolsUsed())
                .totalToolCalls(notebook.getTotalToolCalls())
                .stepSummaries(stepSummaries)
                .evidenceCount(notebook.getEvidence().size())
                .truncatedEvidenceCount((int) notebook.getEvidence().stream()
                        .filter(DeepThinkEvidenceEntry::truncated).count())
                .build();
    }

    private static AgentTraceMetadata.ReflectionTrace buildReflectionTrace(DeepThinkReflectionResult result) {
        if (result == null) {
            return null;
        }
        return AgentTraceMetadata.ReflectionTrace.builder()
                .rounds(result.getRounds())
                .status(mapReflectionStatus(result.getStatus()))
                .summary(truncate(buildReflectionSummary(result), MAX_SUMMARY_LEN))
                .build();
    }

    /**
     * Map raw reflection status to sanitized trace schema values.
     * Raw: CONTINUE, REVISE_PLAN, READY_TO_VERIFY, NEED_USER_CLARIFICATION, SKIPPED
     * Sanitized: CONTINUE, REVISED, SKIPPED
     */
    private static String mapReflectionStatus(String rawStatus) {
        if (rawStatus == null) return null;
        return switch (rawStatus) {
            case "REVISE_PLAN" -> "REVISED";
            case "CONTINUE", "READY_TO_VERIFY" -> "CONTINUE";
            case "SKIPPED" -> "SKIPPED";
            default -> "CONTINUE"; // NEED_USER_CLARIFICATION and any future values
        };
    }

    private static AgentTraceMetadata.VerificationTrace buildVerificationTrace(DeepThinkVerificationResult result) {
        if (result == null) {
            return null;
        }
        List<AgentTraceMetadata.IssueSummary> issues = result.getIssues() != null
                ? result.getIssues().stream()
                .map(issue -> AgentTraceMetadata.IssueSummary.builder()
                        .type(truncate(issue.getType(), MAX_TITLE_LEN))
                        .claim(truncate(issue.getClaim(), MAX_CLAIM_LEN))
                        .build())
                .collect(Collectors.toList())
                : List.of();

        return AgentTraceMetadata.VerificationTrace.builder()
                .passed(result.isPassed())
                .rounds(result.getRounds())
                .issueCount(issues.size())
                .issues(issues)
                .build();
    }

    private static String buildReflectionSummary(DeepThinkReflectionResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.getMissing() != null && !result.getMissing().isEmpty()) {
            sb.append("missing: ").append(String.join("; ", result.getMissing()));
        }
        if (result.getContradictions() != null && !result.getContradictions().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("contradictions: ").append(String.join("; ", result.getContradictions()));
        }
        if (result.getReasonForUserClarification() != null && !result.getReasonForUserClarification().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("clarification: ").append(result.getReasonForUserClarification());
        }
        return sb.length() == 0 ? "OK" : sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
