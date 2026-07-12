package com.yulong.chatagent.support.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * DeepThink 运行追踪摘要，附加在最终回答的 metadata.agentTrace 字段上。
 *
 * <p>仅记录操作级摘要，不暴露原始 plan JSON、原始 tool 响应或私有推理 token。
 * 文本字段有最大长度限制，超出时截断并附加 "…"。</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTraceMetadata {

    private String mode;

    private PlanningTrace planning;
    private ExecutionTrace execution;
    private ReflectionTrace reflection;
    private VerificationTrace verification;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlanningTrace {
        private String goal;
        private int stepCount;
        private List<PlanStepSummary> steps;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlanStepSummary {
        private String id;
        private String title;
        private String status; // COMPLETED | PARTIAL | FAILED | SKIPPED
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecutionTrace {
        private List<String> toolsUsed;
        private int totalToolCalls;
        private List<StepSummary> stepSummaries;
        private int evidenceCount;
        private int truncatedEvidenceCount;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepSummary {
        private String stepId;
        private String conclusion;
        private int toolCallCount;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReflectionTrace {
        private int rounds;
        private String status; // CONTINUE | REVISED | SKIPPED
        private String summary;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VerificationTrace {
        private boolean passed;
        private int rounds;
        private int issueCount;
        private List<IssueSummary> issues;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IssueSummary {
        private String type; // UNSUPPORTED_CLAIM | STALE_DATA | CONTRADICTION | MISSING_SOURCE | TOOL_FAILURE
        private String claim;
    }
}
