package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulong.chatagent.agent.runtime.DirectToolCallbackSource;
import com.yulong.chatagent.memory.application.MemoryApplicationService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MemoryInspectTool implements Tool, DirectToolCallbackSource {
    private static final ToolDefinition DEFINITION = ToolDefinition.builder()
            .name("memoryInspectTool")
            .description("Inspect active long-term memory before an explicit user correction. Use a short literal phrase from the user's message. A unique result includes an internal writable id; ambiguous results intentionally do not.")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}")
            .build();
    private final MemoryApplicationService service;
    private final ObjectMapper objectMapper;
    public MemoryInspectTool(MemoryApplicationService service, ObjectMapper objectMapper) { this.service = service; this.objectMapper = objectMapper; }
    @Override public String getName() { return "MemoryInspectTool"; }
    @Override public String getDescription() { return "Inspect the current user's active long-term memory"; }
    @Override public ToolType getType() { return ToolType.FIXED; }
    @Override public ToolEffectClass effectClass() { return ToolEffectClass.READ_ONLY; }
    @Override public DeadlineMode deadlineMode() { return DeadlineMode.ENFORCED; }
    @Override public List<ToolCallback> getToolCallbacks() { return List.of(new Callback()); }
    private final class Callback implements ToolCallback {
        @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
        @Override public String call(String input) { return error("CONTEXT_MISSING"); }
        @Override public String call(String input, ToolContext context) {
            try {
                Map<String,Object> trusted = context == null ? Map.of() : context.getContext();
                Object userId = trusted.get("userId");
                if (userId == null) return error("CONTEXT_MISSING");
                JsonNode args = objectMapper.readTree(input);
                return objectMapper.writeValueAsString(service.inspect(String.valueOf(userId), args.path("query").asText()));
            } catch (Exception ex) { return error("INVALID_ARGUMENT"); }
        }
        private String error(String code) { return "{\"status\":\"" + code + "\"}"; }
    }
}
