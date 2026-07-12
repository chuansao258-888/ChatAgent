package com.yulong.chatagent.agent.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * Binds execution policy to the exact model-facing callback instance.
 * This avoids a second name registry and keeps ToolDefinition.name(), effect,
 * deadline and returnDirect metadata on one resolution path.
 */
public final class DescribedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolExecutionDescriptor descriptor;

    public DescribedToolCallback(ToolCallback delegate, ToolExecutionDescriptor descriptor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public ToolExecutionDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
