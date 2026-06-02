package com.yulong.chatagent.agent.deepthink;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * DeepThink 计划中的一个步骤。
 * <p>
 * 从 LLM planner 输出解析而来，运行时由 StepExecutor 填充 status 和 conclusion。
 */
@Data
@Builder
public class DeepThinkPlanStep {

    private String id;                  // "S1", "S2", ...
    private String title;
    private String objective;
    private List<String> expectedEvidence;
    private List<String> suggestedTools;
    private List<String> doneCriteria;

    // Runtime fields — populated during execution, not from LLM
    private String status;              // COMPLETED | PARTIAL | FAILED | SKIPPED
    private String conclusion;          // one-sentence finding from step execution
}
