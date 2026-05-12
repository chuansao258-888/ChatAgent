package com.yulong.chatagent.intent.application;

/**
 * 澄清中间态存储接口。
 * <p>
 * 作用是把“上一轮我已经问过用户一个澄清问题，正等他回答”这个状态按 session 暂存起来。
 * 这样下一轮用户再发一句“第一个 / 选第二项 / 我要报销”时，系统就知道：
 * <ul>
 *     <li>这不是一个新的完整问题；</li>
 *     <li>而是在回答上一轮的 clarification prompt。</li>
 * </ul>
 */
public interface PendingIntentResolutionStore {

    /**
     * 读取某个 session 当前是否存在待处理的澄清状态。
     * 如果返回非空，上层就应优先把本轮输入当成“澄清回答”来解释。
     */
    PendingIntentResolution get(String sessionId);

    /**
     * 保存新的澄清状态。
     * 通常发生在 IntentRouter 返回 clarification candidates 之后。
     */
    void save(PendingIntentResolution pendingIntentResolution);

    /**
     * 删除某个 session 的澄清状态。
     * 通常发生在：
     * <ul>
     *     <li>用户已经成功选中候选；</li>
     *     <li>候选失效；</li>
     *     <li>上下文过期。</li>
     * </ul>
     */
    void delete(String sessionId);
}
