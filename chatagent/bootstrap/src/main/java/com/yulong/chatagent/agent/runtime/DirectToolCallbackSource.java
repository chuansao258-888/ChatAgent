package com.yulong.chatagent.agent.runtime;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 直接提供 ToolCallback 的工具能力标记。
 * <p>
 * 普通工具通过 @Tool 方法扫描生成 callback；MCP 等动态工具已经完成封装，
 * 实现这个接口后 AgentToolCallbackFactory 会直接取回调，跳过方法扫描。
 */
public interface DirectToolCallbackSource {

    List<ToolCallback> getToolCallbacks();
}
