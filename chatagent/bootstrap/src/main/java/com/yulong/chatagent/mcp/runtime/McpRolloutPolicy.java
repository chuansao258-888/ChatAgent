package com.yulong.chatagent.mcp.runtime;

import com.yulong.chatagent.support.enums.McpRolloutMode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides whether MCP tools are exposed to one runtime agent during staged rollout.
 */
@Component
public class McpRolloutPolicy {

    private final McpRolloutProperties properties;

    public McpRolloutPolicy(McpRolloutProperties properties) {
        this.properties = properties;
    }

    public boolean isAgentAllowed(String agentId) {
        McpRolloutMode mode = resolveMode();
        return switch (mode) {
            case ALL -> true;
            case NONE -> false;
            case AGENT_ALLOWLIST -> StringUtils.hasText(agentId) && normalizedAllowlist().contains(agentId.trim());
        };
    }

    public McpRolloutMode mode() {
        return resolveMode();
    }

    public Set<String> allowedAgentIds() {
        return normalizedAllowlist();
    }

    private McpRolloutMode resolveMode() {
        return McpRolloutMode.fromValue(properties.getMode());
    }

    private Set<String> normalizedAllowlist() {
        Set<String> allowed = new LinkedHashSet<>();
        if (properties.getAllowedAgentIds() == null) {
            return allowed;
        }
        for (String agentId : properties.getAllowedAgentIds()) {
            if (StringUtils.hasText(agentId)) {
                allowed.add(agentId.trim());
            }
        }
        return allowed;
    }
}
