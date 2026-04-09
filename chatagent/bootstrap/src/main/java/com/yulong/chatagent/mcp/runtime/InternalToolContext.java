package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.trace.TraceContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Local-only tool context extracted from Spring AI's {@link ToolContext}.
 */
public record InternalToolContext(
        String userId,
        String sessionId,
        String turnId,
        String traceId
) {

    public static InternalToolContext from(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return new InternalToolContext(null, null, null, TraceContext.getTraceId());
        }
        Map<String, Object> context = toolContext.getContext();
        return new InternalToolContext(
                stringValue(context.get("userId")),
                stringValue(context.get("sessionId")),
                stringValue(context.get("turnId")),
                TraceContext.getTraceId()
        );
    }

    public boolean isReady() {
        return StringUtils.hasText(userId)
                && StringUtils.hasText(sessionId)
                && StringUtils.hasText(turnId);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
