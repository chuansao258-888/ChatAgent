package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentToolScopeMode;
import com.yulong.chatagent.agent.application.ToolFacadeService;
import com.yulong.chatagent.mcp.runtime.McpRolloutPolicy;
import com.yulong.chatagent.mcp.runtime.McpToolWrapper;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Collection;
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

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallbackFactory.class);

    private final ToolFacadeService toolFacadeService;
    private final McpRolloutPolicy rolloutPolicy;
    private final IntentToolScopeMode toolScopeMode;

    public AgentToolCallbackFactory(ToolFacadeService toolFacadeService,
                                    McpRolloutPolicy rolloutPolicy,
                                    @Value("${chatagent.intent.tool-scope-mode:STRICT_TOOL_ONLY}")
                                    IntentToolScopeMode toolScopeMode) {
        this.toolFacadeService = toolFacadeService;
        this.rolloutPolicy = rolloutPolicy;
        this.toolScopeMode = toolScopeMode;
    }

    /**
     * Builds runtime callbacks from fixed tools plus optionally enabled tools.
     *
     * @param agentConfig persisted agent configuration
     * @return callback list for the current run
     */
    public List<ToolCallback> create(AgentDTO agentConfig) {
        return create(agentConfig, null);
    }

    public List<ToolCallback> create(AgentDTO agentConfig, IntentResolution intentResolution) {
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig, intentResolution);
        Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
        for (Tool tool : runtimeTools) {
            if (tool instanceof DirectToolCallbackSource directToolCallbackSource) {
                for (ToolCallback toolCallback : directToolCallbackSource.getToolCallbacks()) {
                    registerCallback(callbacksByName, toolCallback);
                }
                continue;
            }
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
        return resolveRuntimeTools(agentConfig, null);
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig, IntentResolution intentResolution) {
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());
        List<String> allowedToolNames = agentConfig == null ? List.of() : agentConfig.getAllowedTools();
        List<Tool> optionalTools = toolFacadeService.getOptionalTools();
        log.info("Tool resolution: agentId={}, allowedTools={}, optionalToolsAvailable={}, fixedToolsCount={}, intentResolution={}",
                agentConfig == null ? null : agentConfig.getId(),
                allowedToolNames,
                optionalTools.stream().map(Tool::getName).toList(),
                runtimeTools.size(),
                intentResolution == null ? "null" : intentResolution.kind());
        runtimeTools.removeIf(tool -> shouldHideFixedTool(tool, intentResolution, allowedToolNames, optionalTools, agentConfig));
        if (toolScopeMode == IntentToolScopeMode.STRICT_TOOL_ONLY) {
            return resolveRuntimeToolsStrict(runtimeTools, allowedToolNames, optionalTools, intentResolution, agentConfig);
        }
        return resolveRuntimeToolsWithIntentNarrowing(runtimeTools, allowedToolNames, optionalTools, intentResolution, agentConfig);
    }

    private List<Tool> resolveRuntimeToolsStrict(List<Tool> runtimeTools,
                                                 List<String> allowedToolNames,
                                                 List<Tool> optionalTools,
                                                 IntentResolution intentResolution,
                                                 AgentDTO agentConfig) {
        if (intentResolution == null) {
            // No intent matched → agent chooses freely from all available tools
            appendAllOptionalTools(runtimeTools, optionalTools, agentConfig);
            return runtimeTools;
        }
        if (intentResolution.kind() != IntentKind.TOOL) {
            return runtimeTools;
        }
        allowedToolNames = legacyIntersectAllowedTools(allowedToolNames, intentResolution.allowedTools());
        appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig);
        return runtimeTools;
    }

    private List<Tool> resolveRuntimeToolsWithIntentNarrowing(List<Tool> runtimeTools,
                                                              List<String> allowedToolNames,
                                                              List<Tool> optionalTools,
                                                              IntentResolution intentResolution,
                                                              AgentDTO agentConfig) {
        if (intentResolution == null) {
            // No intent matched → agent chooses freely from all available tools
            appendAllOptionalTools(runtimeTools, optionalTools, agentConfig);
            return runtimeTools;
        }
        if (intentResolution.kind() == IntentKind.TOOL
                && intentResolution.allowedTools() != null
                && !intentResolution.allowedTools().isEmpty()) {
            allowedToolNames = intersectAllowedTools(allowedToolNames, intentResolution.allowedTools());
        }

        appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig);
        return runtimeTools;
    }

    private void appendAllOptionalTools(List<Tool> runtimeTools,
                                        List<Tool> optionalTools,
                                        AgentDTO agentConfig) {
        for (Tool tool : optionalTools) {
            if (isRuntimeAllowed(tool, agentConfig)) {
                runtimeTools.add(tool);
            }
        }
    }

    private void appendOptionalTools(List<Tool> runtimeTools,
                                     List<String> allowedToolNames,
                                     List<Tool> optionalTools,
                                     AgentDTO agentConfig) {
        Map<String, Tool> optionalToolMap = optionalTools
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            // No explicit allowlist → load all available optional tools so the agent can choose freely
            for (Tool tool : optionalTools) {
                if (isRuntimeAllowed(tool, agentConfig)) {
                    runtimeTools.add(tool);
                }
            }
            return;
        }

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null && isRuntimeAllowed(tool, agentConfig)) {
                runtimeTools.add(tool);
            }
        }
    }

    private boolean shouldHideFixedTool(Tool tool,
                                        IntentResolution intentResolution,
                                        List<String> allowedToolNames,
                                        List<Tool> optionalTools,
                                        AgentDTO agentConfig) {
        if (tool == null || !"SessionFileSearchTool".equals(tool.getName())) {
            return false;
        }
        if (intentResolution != null) {
            return intentResolution.kind() != IntentKind.KB;
        }
        return toolScopeMode == IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
                && hasRuntimeOptionalTools(optionalTools, allowedToolNames, agentConfig);
    }

    private boolean hasRuntimeOptionalTools(List<Tool> optionalTools,
                                            List<String> allowedToolNames,
                                            AgentDTO agentConfig) {
        if (optionalTools == null || optionalTools.isEmpty()
                || allowedToolNames == null || allowedToolNames.isEmpty()) {
            return false;
        }
        Map<String, Tool> optionalToolMap = optionalTools.stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));
        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null && isRuntimeAllowed(tool, agentConfig)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRuntimeAllowed(Tool tool, AgentDTO agentConfig) {
        if (!(tool instanceof McpToolWrapper) && (tool == null || tool.getName() == null || !tool.getName().startsWith("mcp_"))) {
            return true;
        }
        return rolloutPolicy.isAgentAllowed(agentConfig == null ? null : agentConfig.getId());
    }

    private List<String> intersectAllowedTools(List<String> agentAllowedTools, List<String> intentAllowedTools) {
        if (intentAllowedTools == null || intentAllowedTools.isEmpty()) {
            return List.of();
        }
        if (agentAllowedTools == null || agentAllowedTools.isEmpty()) {
            return List.of();
        }
        return agentAllowedTools.stream()
                .filter(intentAllowedTools::contains)
                .toList();
    }

    private List<String> legacyIntersectAllowedTools(List<String> agentAllowedTools, List<String> intentAllowedTools) {
        if (intentAllowedTools == null || intentAllowedTools.isEmpty()) {
            return List.of();
        }
        if (agentAllowedTools == null || agentAllowedTools.isEmpty()) {
            return List.copyOf(intentAllowedTools);
        }
        return agentAllowedTools.stream()
                .filter(intentAllowedTools::contains)
                .toList();
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
    }
}
