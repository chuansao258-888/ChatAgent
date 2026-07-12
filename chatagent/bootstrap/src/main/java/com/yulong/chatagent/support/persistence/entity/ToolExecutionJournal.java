package com.yulong.chatagent.support.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * ARRB Phase 1：单条工具派发的 sanitized journal 行（{@code t_tool_execution_journal}）。
 * <p>
 * 该实体只承载规范化身份、状态、哈希和时间戳，从不承载原始工具参数/结果正文。
 * 见 plan ARRB-DEC-010 与 V31 迁移。
 */
@Data
public class ToolExecutionJournal {
    private String id;
    /** 唯一派发键：approved effect 为 approvalId；read-only 为 turnId+toolName+SHA-256(canonical args)。 */
    private String executionKey;
    private String sessionId;
    private String turnId;
    private String approvalId;
    /** 持久化 assistant 工具调用消息的稳定引用（nullable，ON DELETE SET NULL）。 */
    private String assistantMessageId;
    /** ToolCallPreflight 规范化后的 tool call id。 */
    private String toolCallId;
    private String toolName;
    /** 规范化参数的 SHA-256，非原始参数。 */
    private String argumentHash;
    private String effectClass;
    private Integer attempt;
    private String state;
    /** 低基数 safe error code。 */
    private String safeErrorCode;
    /** 配对的 tool-response 消息稳定引用（nullable，ON DELETE SET NULL）。 */
    private String responseMessageId;
    /** 规范化配对响应的 SHA-256，非原始正文。 */
    private String responseHash;
    private LocalDateTime dispatchedAt;
    private Long callDeadlineMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
