package com.yulong.chatagent.agent.deepthink;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * DeepThink 执行计划——由 planner LLM 生成、DeepThinkJsonParser 解析。
 */
@Data
@Builder
public class DeepThinkPlan {

    private String goal;
    private String complexity; // LOW | MEDIUM | HIGH
    private List<String> assumptions;
    private List<DeepThinkPlanStep> steps;
    private List<String> risks;
}
