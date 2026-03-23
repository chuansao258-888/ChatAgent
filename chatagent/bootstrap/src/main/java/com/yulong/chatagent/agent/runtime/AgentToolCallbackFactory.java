package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.admin.application.ToolFacadeService;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creates concrete tool callbacks allowed for one agent runtime.
 */
@Component
public class AgentToolCallbackFactory {

    private final ToolFacadeService toolFacadeService;

    public AgentToolCallbackFactory(ToolFacadeService toolFacadeService) {
        this.toolFacadeService = toolFacadeService;
    }

    /**
     * Builds runtime callbacks from fixed tools plus optionally enabled tools.
     *
     * @param agentConfig persisted agent configuration
     * @return callback list for the current run
     */
    public List<ToolCallback> create(AgentDTO agentConfig) {
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            for (ToolCallback toolCallback : toolCallbacks) {
                registerCallback(callbacksByName, toolCallback);
            }
        }
        return new ArrayList<>(callbacksByName.values());
    }

    /**
     * Resolves the effective tool list allowed for the agent.
     *
     * @param agentConfig persisted agent configuration
     * @return fixed tools plus configured optional tools
     */
    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    /**
     * Unwraps proxied Spring beans so tool metadata is derived from the real target object.
     *
     * @param tool tool bean
     * @return underlying tool target
     */
    private Object resolveToolTarget(Tool tool) {
        Object target = AopProxyUtils.getSingletonTarget(tool);
        return target != null ? target : tool;
    }

    private void registerCallback(Map<String, ToolCallback> callbacksByName, ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        if (!StringUtils.hasText(name)) {
            return;
        }

        callbacksByName.putIfAbsent(name, callback);
        if (name.endsWith("Tool")) {
            String alias = name + "Tool";
            callbacksByName.putIfAbsent(alias, new AliasToolCallback(callback, alias));
        }
    }

    private static final class AliasToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolDefinition aliasDefinition;

        private AliasToolCallback(ToolCallback delegate, String aliasName) {
            this.delegate = delegate;
            ToolDefinition definition = delegate.getToolDefinition();
            this.aliasDefinition = ToolDefinition.builder()
                    .name(aliasName)
                    .description(definition.description())
                    .inputSchema(definition.inputSchema())
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return aliasDefinition;
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
}
