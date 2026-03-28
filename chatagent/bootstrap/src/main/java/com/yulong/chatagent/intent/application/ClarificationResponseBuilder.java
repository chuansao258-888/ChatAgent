package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Builds user-facing clarification prompts from ambiguous routing candidates.
 */
@Component
public class ClarificationResponseBuilder {

    public String build(List<IntentNodeDTO> candidates, String parentPath, boolean retry) {
        if (candidates == null || candidates.isEmpty()) {
            StringBuilder expiredBuilder = new StringBuilder("刚才的候选项已失效，请重新描述一下你的问题。");
            if (StringUtils.hasText(parentPath)) {
                expiredBuilder.append("\n当前范围：").append(parentPath);
            }
            return expiredBuilder.toString();
        }

        StringBuilder builder = new StringBuilder();
        if (retry) {
            builder.append("我还没有识别出你想选哪一项，请直接回复序号或名称。");
        } else {
            builder.append("我需要先确认一下你的问题属于哪一类。");
        }
        if (StringUtils.hasText(parentPath)) {
            builder.append("\n当前范围：").append(parentPath);
        }
        builder.append("\n请选择：");
        for (int i = 0; i < candidates.size(); i++) {
            IntentNodeDTO candidate = candidates.get(i);
            builder.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(candidate.getName());
            if (candidate != null && StringUtils.hasText(candidate.getDescription())) {
                builder.append(" - ").append(candidate.getDescription());
            }
        }
        return builder.toString();
    }
}
