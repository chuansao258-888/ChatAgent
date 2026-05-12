package com.yulong.chatagent.intent.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 单个 session 的待澄清状态。
 * <p>
 * 它不是完整会话上下文，只保存继续完成一次 clarification 所需的最小信息：
 * <ul>
 *     <li>{@code candidateNodeIds}：上轮展示给用户选的候选节点；</li>
 *     <li>{@code originalQuery}：真正需要被继续路由的原始问题；</li>
 *     <li>{@code parentPath}：给用户看的“当前范围”；</li>
 *     <li>{@code expiresAt}：这个澄清状态的过期时间。</li>
 * </ul>
 * 这也是为什么它可以安全地放在 Redis 里做短期缓存，而不需要进入长期会话记忆。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingIntentResolution {
    /**
     * 归属 session。
     * clarification 状态不是全局的，而是某个对话会话的局部中间态。
     */
    private String sessionId;
    /**
     * 上一轮返回给用户的候选节点 ID 列表。
     * 下一轮用户回复时，会先根据这些 ID 恢复候选节点，再尝试匹配“第一个/选报销”之类的回答。
     */
    private List<String> candidateNodeIds;
    /**
     * 触发澄清时的原始 query。
     * 选中候选后，系统继续路由时用的是这条原始 query，而不是用户那句简短回答。
     */
    private String originalQuery;
    /**
     * 当前候选所在的父级路径标签，例如“报销 > 差旅”。
     * 主要用于生成面向用户的澄清提示文案。
     */
    private String parentPath;
    /**
     * 过期时间。
     * 超时后澄清状态会被丢弃，避免用户很久以后回复一句“第一个”却误命中旧上下文。
     */
    private Instant expiresAt;
}
