package com.yulong.chatagent.agent.tools;

import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ARRB Phase 1（cross-review F-1/F-4）：按 model-facing callback 名解析
 * {@link ToolExecutionDescriptor}。
 * <p>
 * 内置 {@link Tool} 通过 {@code effectClass()} 显式声明副作用；MCP 等未声明的回调默认
 * {@link ToolEffectClass#UNKNOWN} + {@link DeadlineMode#UNSUPPORTED}（ARRB-DEC-017/018）。
 * 该解析器在一次 run 内构造一次，coordinator 据此判断"是否需要确认"与"是否可重试"。
 * <p>
 * 这里不引入新的注册表抽象：它只是一个有界查找映射，从既有 {@code List<ToolCallback>}
 * 派生，调用方（coordinator）持有它。
 */
public final class ToolExecutionDescriptorResolver {

    private final Map<String, ToolExecutionDescriptor> byName;

    public ToolExecutionDescriptorResolver(List<ToolCallback> callbacks) {
        this.byName = new HashMap<>();
        if (callbacks == null) {
            return;
        }
        for (ToolCallback cb : callbacks) {
            if (cb == null || cb.getToolDefinition() == null || cb.getToolDefinition().name() == null) {
                continue;
            }
            String name = cb.getToolDefinition().name();
            ToolExecutionDescriptor descriptor = cb instanceof DescribedToolCallback described
                    ? described.descriptor()
                    : ToolExecutionDescriptor.unknown(name);
            byName.put(name, descriptor);
        }
    }

    public ToolExecutionDescriptor resolve(String callbackName) {
        ToolExecutionDescriptor d = byName.get(callbackName);
        return d != null ? d : ToolExecutionDescriptor.unknown(callbackName);
    }

    /** 是否需要先精确确认才派发（非 READ_ONLY，含 UNKNOWN）。 */
    public boolean requiresConfirmation(String callbackName) {
        return resolve(callbackName).requiresConfirmation();
    }
}
