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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 为单次 Agent 运行生成真正暴露给模型的工具回调列表。
 * <p>
 * 工具是否可用由三层条件共同决定：Agent 配置的 allowedTools、意图路由的工具范围、
 * 以及 MCP rollout 策略。最终输出的是 Spring AI 可识别的 {@link ToolCallback}。
 * <p>
 * 这里要区分两类“名字”：
 * <ul>
 *     <li>{@link Tool#getName()}：后端工具 bean 的名字，用于 Agent 配置、筛选、权限判断。</li>
 *     <li>{@code ToolCallback.ToolDefinition.name()}：最终暴露给模型的函数名，也就是模型 tool call 里会写的名字。</li>
 * </ul>
 * 一个 {@link Tool} 对象里可以有多个 {@code @Tool} 方法，因此一个后端工具可能展开成多个
 * {@link ToolCallback}。
 */
@Component
public class AgentToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCallbackFactory.class);

    private final ToolFacadeService toolFacadeService;
    private final McpRolloutPolicy rolloutPolicy;
    private final IntentToolScopeMode toolScopeMode;

    public AgentToolCallbackFactory(ToolFacadeService toolFacadeService,
                                    McpRolloutPolicy rolloutPolicy,
                                    @Value("${chatagent.intent.tool-scope-mode:AGENT_DEFAULT_WITH_INTENT_NARROWING}")
                                    IntentToolScopeMode toolScopeMode) {
        this.toolFacadeService = toolFacadeService;
        this.rolloutPolicy = rolloutPolicy;
        this.toolScopeMode = toolScopeMode;
    }

    /**
     * 从 fixed tools 和 optional tools 中解析本轮可用工具，并转换为 Spring AI ToolCallback。
     *
     * @param agentConfig Agent 配置
     * @return 本轮运行可用的工具回调列表
     */
    public List<ToolCallback> create(AgentDTO agentConfig) {
        return create(agentConfig, null);
    }

    public List<ToolCallback> create(AgentDTO agentConfig, IntentResolution intentResolution) {
        // 第一步：先从“后端工具对象”维度筛出本轮允许使用的 Tool。
        // 这里的筛选依据是 Tool.getName()，也就是 AgentDTO.allowedTools 里配置的名字。
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig, intentResolution);

        // 第二步：把 Tool 展开成 Spring AI 的 ToolCallback。
        // key 用的是 ToolDefinition.name()，因为最终模型调用时也是按这个名字发起 tool call。
        // LinkedHashMap 可以保持注册顺序，同时按模型可见函数名去重。
        Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
        for (Tool tool : runtimeTools) {
            // MCP 这类工具已经自己包装成 ToolCallback，就不再走 @Tool 方法扫描。
            // 这类 Tool 通常是“外部 MCP 工具 -> 已经生成好的 callback”。
            if (tool instanceof DirectToolCallbackSource directToolCallbackSource) {
                for (ToolCallback toolCallback : directToolCallbackSource.getToolCallbacks()) {
                    registerCallback(callbacksByName, toolCallback);
                }
                continue;
            }
            // 普通内置工具通过 Spring AI 的 MethodToolCallbackProvider 扫描 @Tool 注解方法。
            // 一个工具类里有几个 @Tool 方法，这里就会生成几个 ToolCallback entry。
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
     * 解析当前 Agent 实际可用的工具对象。
     *
     * @param agentConfig Agent 配置
     * @return 固定工具 + 当前允许的可选工具
     */
    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        return resolveRuntimeTools(agentConfig, null);
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig, IntentResolution intentResolution) {
        // fixed tools 默认先加入，例如 SessionFileSearchTool。
        // 注意：fixed 不是“永远暴露给模型”，后面 still 会按 intent 决定是否隐藏。
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        // allowedToolNames 来自 Agent 配置，语义是“这个 Agent 后台允许用哪些 optional Tool”。
        // 这里的名字对应 Tool.getName()，不是 @Tool 方法暴露给模型的函数名。
        List<String> allowedToolNames = agentConfig == null ? List.of() : agentConfig.getAllowedTools();

        // optionalTools 是系统里所有可选工具的候选池，后面会用 allowedToolNames / intent 再过滤。
        List<Tool> optionalTools = toolFacadeService.getOptionalTools();
        log.info("Tool resolution: agentId={}, allowedTools={}, optionalToolsAvailable={}, fixedToolsCount={}, intentResolution={}",
                agentConfig == null ? null : agentConfig.getId(),
                allowedToolNames,
                optionalTools.stream().map(Tool::getName).toList(),
                runtimeTools.size(),
                intentResolution == null ? "null" : intentResolution.kind());

        // fixed tool 也可能被隐藏：典型例子是 SessionFileSearchTool。
        // 如果本轮不是 KB 意图，就不该把文件检索工具塞给模型，避免模型乱检索。
        runtimeTools.removeIf(tool -> shouldHideFixedTool(tool, intentResolution, allowedToolNames, optionalTools, agentConfig));

        // 根据配置选择两种工具开放策略：
        // 1. STRICT_TOOL_ONLY：只有 TOOL 意图才开放 optional tools。
        // 2. AGENT_DEFAULT_WITH_INTENT_NARROWING：默认按 Agent 配置开放，再由意图结果收窄。
        if (toolScopeMode == IntentToolScopeMode.STRICT_TOOL_ONLY) {
            return resolveRuntimeToolsStrict(runtimeTools, allowedToolNames, optionalTools, intentResolution, agentConfig);
        }
        // 非 STRICT 模式允许 Agent 默认工具和意图收窄策略共同生效。
        return resolveRuntimeToolsWithIntentNarrowing(runtimeTools, allowedToolNames, optionalTools, intentResolution, agentConfig);
    }

    private List<Tool> resolveRuntimeToolsStrict(List<Tool> runtimeTools,
                                                 List<String> allowedToolNames,
                                                 List<Tool> optionalTools,
                                                 IntentResolution intentResolution,
                                                 AgentDTO agentConfig) {
        if (intentResolution == null) {
            // 没有意图结果时，回到 Agent 配置的默认工具集合。
            // Agent allowedTools 为空表示 Agent 层不限制，也就是默认可用全部 optional tools。
            appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig, true);
            return runtimeTools;
        }
        if (intentResolution.kind() != IntentKind.TOOL) {
            // STRICT 模式下，只有 TOOL 意图会开放 optional tools；KB/CHAT 等意图只保留 fixed tools。
            // 此时前面 shouldHideFixedTool 已经处理过 SessionFileSearchTool 是否要留下。
            return runtimeTools;
        }
        // TOOL 意图会和 Agent 自身 allowlist 做交集，避免意图路由扩大 Agent 原本权限。
        // Agent allowedTools 为空表示 Agent 层不限制，所以直接采用 intent 给出的工具范围。
        allowedToolNames = intersectAllowedTools(allowedToolNames, intentResolution.allowedTools());
        appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig, false);
        return runtimeTools;
    }

    private List<Tool> resolveRuntimeToolsWithIntentNarrowing(List<Tool> runtimeTools,
                                                              List<String> allowedToolNames,
                                                              List<Tool> optionalTools,
                                                              IntentResolution intentResolution,
                                                              AgentDTO agentConfig) {
        if (intentResolution == null) {
            // 没有意图结果时，按 Agent 配置加载所有可用 optional tools。
            // 这条路径常用于没有开启/没有拿到意图路由结果时的兜底。
            appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig, true);
            return runtimeTools;
        }
        if (intentResolution.kind() == IntentKind.TOOL
                && intentResolution.allowedTools() != null
                && !intentResolution.allowedTools().isEmpty()) {
            // 如果意图路由明确说“这轮只需要某些工具”，就和 Agent 自己的 allowedTools 取交集。
            // Agent allowedTools 为空表示 Agent 层不限制，所以这种情况下直接采用 intent allowedTools。
            allowedToolNames = intersectAllowedTools(allowedToolNames, intentResolution.allowedTools());
            // 这里 emptyAllowedToolsMeansAll 必须是 false：
            // intent 已经明确收窄后，空交集表示“本轮没有 optional tool”，不是“全部开放”。
            appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig, false);
            return runtimeTools;
        }

        // 非 TOOL 意图不会进上面的交集分支，因此仍然按 Agent allowedTools 加 optional tools。
        // 这就是该模式名字里 “Agent default” 的含义。
        appendOptionalTools(runtimeTools, allowedToolNames, optionalTools, agentConfig, true);
        return runtimeTools;
    }

    private void appendOptionalTools(List<Tool> runtimeTools,
                                     List<String> allowedToolNames,
                                     List<Tool> optionalTools,
                                     AgentDTO agentConfig,
                                     boolean emptyAllowedToolsMeansAll) {
        // 建一个后端工具名 -> Tool 对象的索引，后面按 Agent allowedTools 的名字快速找到候选工具。
        // key 是 Tool.getName()，例如 emailTool；value 是具体 Tool bean。
        Map<String, Tool> optionalToolMap = optionalTools
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            // 空列表有两种语义：
            // 1. Agent 默认池为空：表示 Agent 层不限制，默认开放所有 optional tools。
            // 2. intent 收窄后的结果为空：表示交集为空，本轮不开放 optional tools。
            if (!emptyAllowedToolsMeansAll) {
                return;
            }
            for (Tool tool : optionalTools) {
                if (isRuntimeAllowed(tool, agentConfig)) {
                    runtimeTools.add(tool);
                }
            }
            return;
        }

        for (String toolName : allowedToolNames) {
            // 这里的 toolName 来自 Agent 配置或 intent 交集，仍然是 Tool.getName() 这一层名字。
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
        // 会话文件检索虽然是 fixed tool，但在非 KB 意图下可能需要隐藏，避免模型无谓检索知识库。
        if (tool == null || !"SessionFileSearchTool".equals(tool.getName())) {
            return false;
        }
        if (intentResolution != null) {
            // 有明确意图时最好判断：只有 KB 意图才保留文件检索工具。
            return intentResolution.kind() != IntentKind.KB;
        }
        // 没有意图结果时，如果当前 Agent 已经有可用 optional tools，
        // AGENT_DEFAULT_WITH_INTENT_NARROWING 模式会倾向于隐藏 fixed 的文件检索工具。
        return toolScopeMode == IntentToolScopeMode.AGENT_DEFAULT_WITH_INTENT_NARROWING
                && hasRuntimeOptionalTools(optionalTools, allowedToolNames, agentConfig);
    }

    private boolean hasRuntimeOptionalTools(List<Tool> optionalTools,
                                            List<String> allowedToolNames,
                                            AgentDTO agentConfig) {
        if (optionalTools == null || optionalTools.isEmpty()) {
            return false;
        }
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            // Agent allowedTools 为空表示默认全量工具池；只要存在运行期允许的 optional tool，就算有可用工具。
            return optionalTools.stream().anyMatch(tool -> isRuntimeAllowed(tool, agentConfig));
        }
        // 判断 allowedTools 里是否至少有一个真实存在、且运行期允许的 optional tool。
        // 用在隐藏 fixed 文件检索工具的判断里：有可用 optional tool 时，默认不把 KB 检索混进去。
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
        // MCP 工具受 rollout 策略控制；普通内置工具默认允许。
        // McpToolWrapper 或名字以 mcp_ 开头的工具，都需要通过 rolloutPolicy 判断当前 Agent 是否放量。
        if (!(tool instanceof McpToolWrapper) && (tool == null || tool.getName() == null || !tool.getName().startsWith("mcp_"))) {
            return true;
        }
        return rolloutPolicy.isAgentAllowed(agentConfig == null ? null : agentConfig.getId());
    }

    private List<String> intersectAllowedTools(List<String> agentAllowedTools, List<String> intentAllowedTools) {
        // Agent allowedTools 的空列表表示“Agent 层不限制”，即默认拥有全部 optional tools。
        // 因此 intent allowedTools 非空时，可以直接作为收窄后的工具集合。
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
     * 解开 Spring AOP 代理，确保 @Tool 元数据来自真实工具对象。
     *
     * @param tool 工具 bean
     * @return 原始工具对象；无法解包时返回 bean 本身
     */
    private Object resolveToolTarget(Tool tool) {
        Object target = AopProxyUtils.getSingletonTarget(tool);
        return target != null ? target : tool;
    }

    private void registerCallback(Map<String, ToolCallback> callbacksByName, ToolCallback callback) {
        // 按工具名去重，保证最终传给模型的 tool schema 没有重复名称。
        // 这里取的是 ToolDefinition.name()，也就是模型真正看到和调用的函数名，例如 sendEmail。
        String name = callback.getToolDefinition().name();
        if (!StringUtils.hasText(name)) {
            return;
        }

        // 如果两个 ToolCallback 暴露了同名函数，只保留先注册的那个，避免 Spring AI / 模型侧名字冲突。
        callbacksByName.putIfAbsent(name, callback);
    }
}
