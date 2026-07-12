package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.DirectToolCallbackSource;
import com.yulong.chatagent.memory.application.MemoryApplicationService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class MemoryCorrectTool implements Tool, DirectToolCallbackSource {
    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name("memoryCorrectTool")
            .description("Correct one uniquely inspected long-term memory only when the current user message explicitly states the correction and desired new content. Copy evidenceQuote literally from that message.")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"memoryId\":{\"type\":\"string\"},\"expectedUpdatedAt\":{\"type\":\"string\"},\"type\":{\"type\":\"string\",\"enum\":[\"fact\",\"preference\"]},\"newContent\":{\"type\":\"string\"},\"evidenceQuote\":{\"type\":\"string\"}},\"required\":[\"memoryId\",\"expectedUpdatedAt\",\"newContent\",\"evidenceQuote\"],\"additionalProperties\":false}")
            .build();
    private final MemoryApplicationService service;
    private final ObjectMapper objectMapper;
    public MemoryCorrectTool(MemoryApplicationService service, ObjectMapper objectMapper) { this.service = service; this.objectMapper = objectMapper; }
    @Override public String getName() { return "MemoryCorrectTool"; }
    @Override public String getDescription() { return "Correct the current user's memory with optimistic concurrency"; }
    @Override public ToolType getType() { return ToolType.FIXED; }
    @Override public ToolEffectClass effectClass() { return ToolEffectClass.IDEMPOTENT; }
    @Override public DeadlineMode deadlineMode() { return DeadlineMode.ENFORCED; }
    @Override public List<ToolCallback> getToolCallbacks() { return List.of(new Callback()); }
    private final class Callback implements ToolCallback {
        @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
        @Override public String call(String input) { return error("CONTEXT_MISSING"); }
        @Override public String call(String input, ToolContext context) {
            try {
                Map<String,Object> trusted = context == null ? Map.of() : context.getContext();
                Object userId = trusted.get("userId"); Object raw = trusted.get("currentUserInput");
                if (userId == null || raw == null) return error("CONTEXT_MISSING");
                JsonNode a = objectMapper.readTree(input);
                String type = a.hasNonNull("type") ? a.get("type").asText() : null;
                return objectMapper.writeValueAsString(service.correctFromConversation(
                        String.valueOf(userId), String.valueOf(raw), a.path("evidenceQuote").asText(),
                        a.path("memoryId").asText(), LocalDateTime.parse(a.path("expectedUpdatedAt").asText()),
                        type, a.path("newContent").asText()));
            } catch (Exception ex) { return error("INVALID_ARGUMENT"); }
        }
        private String error(String code) { return "{\"status\":\"" + code + "\"}"; }
    }
}
