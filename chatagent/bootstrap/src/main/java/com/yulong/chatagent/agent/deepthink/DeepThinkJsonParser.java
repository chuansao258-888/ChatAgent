package com.yulong.chatagent.agent.deepthink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 planner LLM 的 JSON 输出为 {@link DeepThinkPlan}。
 * <p>
 * 处理 markdown code fence 包裹（```json ... ```），
 * 验证必要字段，将 steps 裁剪到 maxPlanItems。
 */
@Slf4j
public class DeepThinkJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 LLM 原始输出解析计划。
     *
     * @param llmOutput    LLM 返回的原始文本（可能包含 markdown code fence）
     * @param maxPlanItems 最大计划步骤数，超出时截断
     * @return 解析后的计划，如果解析失败返回 null
     */
    public static DeepThinkPlan parsePlan(String llmOutput, int maxPlanItems) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        String json = extractJson(llmOutput);
        if (json == null) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(json);

            DeepThinkPlan plan = DeepThinkPlan.builder()
                    .goal(textField(root, "goal"))
                    .complexity(textField(root, "complexity"))
                    .assumptions(stringListField(root, "assumptions"))
                    .steps(parseSteps(root.get("steps"), maxPlanItems))
                    .risks(stringListField(root, "risks"))
                    .build();

            // Validate minimum required fields
            if (plan.getGoal() == null || plan.getGoal().isBlank()) {
                log.warn("DeepThink plan missing goal field");
                return null;
            }

            return plan;
        } catch (Exception e) {
            log.warn("Failed to parse DeepThink plan JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the reflection stage JSON result.
     */
    public static DeepThinkReflectionResult parseReflectionResult(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        String json = extractJson(llmOutput);
        if (json == null) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(json);
            String status = textField(root, "status");
            if (status == null || status.isBlank()) {
                log.warn("DeepThink reflection result missing status field");
                return null;
            }

            return DeepThinkReflectionResult.builder()
                    .status(status)
                    .covered(stringListField(root, "covered"))
                    .missing(stringListField(root, "missing"))
                    .contradictions(stringListField(root, "contradictions"))
                    .revisedSteps(parseSteps(root.get("revisedSteps"), Integer.MAX_VALUE))
                    .reasonForUserClarification(textField(root, "reasonForUserClarification"))
                    .skipped(false)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse DeepThink reflection JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the verification stage JSON result.
     */
    public static DeepThinkVerificationResult parseVerificationResult(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        String json = extractJson(llmOutput);
        if (json == null) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode passedNode = root.get("passed");
            if (passedNode == null || !passedNode.isBoolean()) {
                log.warn("DeepThink verification result missing boolean passed field");
                return null;
            }

            return DeepThinkVerificationResult.builder()
                    .passed(passedNode.asBoolean())
                    .issues(parseVerificationIssues(root.get("issues")))
                    .requiredFollowUpActions(parseSteps(root.get("requiredFollowUpActions"), Integer.MAX_VALUE))
                    .skipped(false)
                    .caveat(textField(root, "caveat"))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse DeepThink verification JSON: {}", e.getMessage());
            return null;
        }
    }

    private static List<DeepThinkPlanStep> parseSteps(JsonNode stepsNode, int maxItems) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return List.of();
        }
        List<DeepThinkPlanStep> steps = new ArrayList<>();
        int limit = Math.min(stepsNode.size(), maxItems);
        for (int i = 0; i < limit; i++) {
            steps.add(parseStep(stepsNode.get(i)));
        }
        return steps;
    }

    private static List<DeepThinkVerificationResult.Issue> parseVerificationIssues(JsonNode issuesNode) {
        if (issuesNode == null || !issuesNode.isArray()) {
            return List.of();
        }
        List<DeepThinkVerificationResult.Issue> issues = new ArrayList<>();
        for (JsonNode issueNode : issuesNode) {
            issues.add(DeepThinkVerificationResult.Issue.builder()
                    .type(textField(issueNode, "type"))
                    .claim(textField(issueNode, "claim"))
                    .fix(textField(issueNode, "fix"))
                    .build());
        }
        return issues;
    }

    private static DeepThinkPlanStep parseStep(JsonNode node) {
        return DeepThinkPlanStep.builder()
                .id(textField(node, "id"))
                .title(textField(node, "title"))
                .objective(textField(node, "objective"))
                .expectedEvidence(stringListField(node, "expectedEvidence"))
                .suggestedTools(stringListField(node, "suggestedTools"))
                .doneCriteria(stringListField(node, "doneCriteria"))
                .build();
    }

    /**
     * Extract JSON string from LLM output, handling markdown code fences.
     */
    static String extractJson(String output) {
        String trimmed = output.trim();

        // Try to extract from ```json ... ``` fence
        int fenceStart = trimmed.indexOf("```json");
        if (fenceStart >= 0) {
            int jsonStart = fenceStart + "```json".length();
            int fenceEnd = trimmed.indexOf("```", jsonStart);
            if (fenceEnd > jsonStart) {
                return trimmed.substring(jsonStart, fenceEnd).trim();
            }
        }

        // Try to extract from ``` ... ``` fence
        fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int jsonStart = trimmed.indexOf('\n', fenceStart);
            if (jsonStart > fenceStart) {
                jsonStart++; // skip the newline
                int fenceEnd = trimmed.indexOf("```", jsonStart);
                if (fenceEnd > jsonStart) {
                    return trimmed.substring(jsonStart, fenceEnd).trim();
                }
            }
        }

        // Try raw JSON — look for opening brace
        int braceStart = trimmed.indexOf('{');
        if (braceStart >= 0) {
            int braceEnd = trimmed.lastIndexOf('}');
            if (braceEnd > braceStart) {
                return trimmed.substring(braceStart, braceEnd + 1);
            }
        }

        log.warn("Could not extract JSON from LLM output");
        return null;
    }

    private static String textField(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static List<String> stringListField(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isArray()) {
            return List.of();
        }
        try {
            return MAPPER.convertValue(child, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

}
