package com.yulong.chatagent.agent;

/**
 * Agent 运行失败异常。
 * <p>
 * 除了原始 cause，它还携带已经构造好的 {@link AgentRunResult}，方便异步失败处理
 * 在补发错误消息时仍能记录一致的指标。
 */
public class AgentRunException extends RuntimeException {

    private final AgentRunResult result;

    public AgentRunException(String message, Throwable cause, AgentRunResult result) {
        super(message, cause);
        this.result = result;
    }

    public AgentRunResult getResult() {
        return result;
    }
}
