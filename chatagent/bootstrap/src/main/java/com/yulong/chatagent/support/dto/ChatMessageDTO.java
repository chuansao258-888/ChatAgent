package com.yulong.chatagent.support.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.rag.model.CitationMetadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ChatMessageDTO {
    private String id;

    private String sessionId;

    private String turnId;

    private Long turnSeq;

    private RoleType role;

    private String content;

    private MetaData metadata;

    private Long seqNo;

    private Boolean turnCompleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class MetaData {
        private ToolResponseMessage.ToolResponse toolResponse;
        private List<AssistantMessage.ToolCall> toolCalls;
        private List<CitationMetadata> citations;
        private AgentExecutionMode executionMode;
        /** true = 不在默认聊天历史中展示；DeepThink 内部 tool_call/tool_response 消息标记。 */
        private Boolean internal;
        /** DeepThink 阶段标签："PLAN" | "EXECUTE" | "REFLECT" | "VERIFY" */
        private String deepThinkPhase;
        /** 计划步骤 ID，如 "S1"、"S2" */
        private String planStepId;
        /** DeepThink 运行追踪摘要，仅附加在最终回答上 */
        private AgentTraceMetadata agentTrace;
    }

    @Getter
    @AllArgsConstructor
    public enum RoleType {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system"),
        TOOL("tool");

        @JsonValue
        private final String role;

        public static RoleType fromRole(String role) {
            for (RoleType value : values()) {
                if (value.role.equals(role)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Invalid role: " + role);
        }
    }
}
