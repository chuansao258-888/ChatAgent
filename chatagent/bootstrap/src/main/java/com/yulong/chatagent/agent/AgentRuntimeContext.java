package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 创建 {@link ChatAgent} 所需的不可变运行时快照。
 * <p>
 * 这个 record 是 Agent 启动包：上游 loader 把配置、记忆、摘要和工具都整理好，
 * 工厂只需要把它原样传给 ChatAgent 构造器。
 *
 * @param agentId Agent 配置 ID
 * @param name Agent 展示名称
 * @param description Agent 描述
 * @param systemPrompt 已拼装完成的系统提示词
 * @param model 目标模型名称
 * @param maxMessages 运行期消息窗口大小
 * @param memory 从数据库恢复出的 L1 短期记忆
 * @param toolCallbacks 本轮可用工具回调
 * @param sessionFileSummary 会话附件摘要
 * @param sessionSummary L2 历史摘要
 * @param userProfileSummary L3 用户画像摘要
 * @param executionMode 本轮用户选择并解析后的执行模式
 */
public record AgentRuntimeContext(
        String agentId,
        String name,
        String description,
        String systemPrompt,
        String model,
        Integer maxMessages,
        List<Message> memory,
        List<ToolCallback> toolCallbacks,
        String sessionFileSummary,
        String sessionSummary,
        String userProfileSummary,
        AgentExecutionMode executionMode
) {
}
