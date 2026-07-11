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

    public String buildReleased(ClarificationResolver.ReplyOutcome outcome, String userInput) {
        boolean chinese = shouldUseChinese(userInput);
        if (outcome == ClarificationResolver.ReplyOutcome.CANCEL) {
            return chinese
                    ? "好的，已取消刚才的选择。你可以直接提出新的问题。"
                    : "Okay, I cancelled that selection. You can ask a new question.";
        }
        return chinese
                ? "这些选项都不合适。请换一种方式描述你的问题。"
                : "None of those options fit. Please describe your question another way.";
    }

    public String buildRetryLimitReached(String userInput) {
        return shouldUseChinese(userInput)
                ? "我仍无法确定你的选择，已结束本次澄清。请重新完整描述你的问题。"
                : "I still could not determine your selection, so I ended this clarification. Please restate your full question.";
    }

    public String buildExecutionInfoMissing(List<MissingDimension> missingDimensions, String userInput) {
        List<MissingDimension> missing = missingDimensions == null ? List.of() : missingDimensions;
        boolean chinese = shouldUseChinese(userInput);
        if (missing.contains(MissingDimension.CONFIRMATION)
                || missing.contains(MissingDimension.ACTION)) {
            return chinese
                    ? "这个请求可能产生外部修改或操作。请明确确认是否要继续执行。"
                    : "This request may make an external change. Please explicitly confirm whether I should proceed.";
        }
        if (missing.contains(MissingDimension.SOURCE)) {
            return chinese
                    ? "请说明应使用哪个来源，例如已上传文件或指定知识范围。"
                    : "Please specify which source to use, such as an uploaded file or the relevant knowledge scope.";
        }
        if (missing.contains(MissingDimension.OBJECT)) {
            return chinese
                    ? "请说明你指的是哪个具体对象或记录。"
                    : "Please identify the specific object or record you mean.";
        }
        if (missing.contains(MissingDimension.TIME_OR_VERSION)) {
            return chinese
                    ? "请说明需要哪个时间点或版本。"
                    : "Please specify the relevant time or version.";
        }
        return chinese
                ? "我已识别到请求方向，但还缺少安全执行所需的信息。请补充具体对象、来源或操作范围。"
                : "I understand the request, but information required for safe execution is missing. Please provide the object, source, or action scope.";
    }

    public String buildMultiIntentConflict(String userInput) {
        return shouldUseChinese(userInput)
                ? "这些事项无法安全地同时处理。请说明先处理哪一项，或分别发送。"
                : "These requests cannot be handled safely together. Please say which one comes first or send them separately.";
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
