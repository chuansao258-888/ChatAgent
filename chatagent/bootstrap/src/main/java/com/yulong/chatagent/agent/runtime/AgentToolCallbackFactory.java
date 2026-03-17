package com.yulong.chatagent.agent.runtime;

import com.yulong.chatagent.agent.tools.Tool;
import com.yulong.chatagent.admin.application.ToolFacadeService;
import com.yulong.chatagent.support.dto.AgentDTO;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentToolCallbackFactory {

    private final ToolFacadeService toolFacadeService;

    public AgentToolCallbackFactory(ToolFacadeService toolFacadeService) {
        this.toolFacadeService = toolFacadeService;
    }

    public List<ToolCallback> create(AgentDTO agentConfig) {
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

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

    private Object resolveToolTarget(Tool tool) {
        Object target = AopProxyUtils.getSingletonTarget(tool);
        return target != null ? target : tool;
    }
}
