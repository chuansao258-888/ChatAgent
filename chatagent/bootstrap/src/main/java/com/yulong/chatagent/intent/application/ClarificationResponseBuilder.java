package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 澄清回复文案构造器。
 * <p>
 * 它把一组候选意图节点转换成面向用户的 direct reply 文本，
 * 让用户明确知道自己现在需要“选哪一项”，而不是直接进入 Agent。
 * <p>
 * 当前文案分三类：
 * <ul>
 *     <li>首次澄清：告诉用户“我需要先确认一下”；</li>
 *     <li>重试澄清：告诉用户“我还没识别出来，请直接回复序号或名称”；</li>
 *     <li>候选失效：告诉用户“刚才的候选项已失效，请重新描述问题”。</li>
 * </ul>
 */
@Component
public class ClarificationResponseBuilder {

    /**
     * 构造给用户看的澄清提示文本。
     * <p>
     * 这个文本会被 prepare 阶段直接作为 direct reply 返回，
     * 因此它承担的是“暂停主链并引导用户补充选择”的职责，而不是最终答案。
     */
    public String build(List<IntentNodeDTO> candidates,
                        String parentPath,
                        boolean retry,
                        String userInput) {
        boolean chinese = shouldUseChinese(userInput);
        if (candidates == null || candidates.isEmpty()) {
            // 候选为空通常说明：
            // 1. 上一次澄清状态已过期；
            // 2. 最新 snapshot 里已经找不到旧候选节点。
            StringBuilder expiredBuilder = new StringBuilder(chinese
                    ? "刚才的候选项已失效，请重新描述一下你的问题。"
                    : "The previous options are no longer available. Please describe your question again.");
            if (StringUtils.hasText(parentPath)) {
                expiredBuilder.append(chinese ? "\n当前范围：" : "\nCurrent scope: ").append(parentPath);
            }
            return expiredBuilder.toString();
        }

        StringBuilder builder = new StringBuilder();
        if (retry) {
            // retry=true 表示用户已经回了一次，但 ClarificationResolver 还是没法定位到候选。
            builder.append(chinese
                    ? "我还没有识别出你想选哪一项，请直接回复序号或名称。"
                    : "I could not identify your selection. Please reply with its number or name.");
        } else {
            // 首次澄清时，语气更偏向“解释为什么要先问一句”。
            builder.append(chinese
                    ? "我需要先确认一下你的问题属于哪一类。"
                    : "I need to confirm which category your question belongs to.");
        }
        if (StringUtils.hasText(parentPath)) {
            // parentPath 用来提示“当前正在哪个父级范围内选”，帮助用户缩小理解范围。
            builder.append(chinese ? "\n当前范围：" : "\nCurrent scope: ").append(parentPath);
        }
        builder.append(chinese ? "\n请选择：" : "\nPlease choose:");
        for (int i = 0; i < candidates.size(); i++) {
            IntentNodeDTO candidate = candidates.get(i);
            // 这里故意按序号列出，方便下一轮用户直接回答“第一个/第二个”。
            builder.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(candidate.getName());
            if (candidate != null && StringUtils.hasText(candidate.getDescription())) {
                // description 主要是给用户补充区分信息，减少二次澄清概率。
                builder.append(" - ").append(candidate.getDescription());
            }
        }
        return builder.toString();
    }

    private boolean shouldUseChinese(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        int hanCount = 0;
        int latinCount = 0;
        for (int i = 0; i < userInput.length(); i++) {
            char character = userInput.charAt(i);
            if (Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) {
                hanCount++;
            } else if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')) {
                latinCount++;
            }
        }
        return hanCount > 0 && hanCount * 2 > latinCount;
    }
}
