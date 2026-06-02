package com.yulong.chatagent.chat.routing;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;

import java.util.List;

/**
 * LLM 路由服务的业务接口。
 *
 * <p>同步方法用于 Agent 内部需要完整 ChatResponse 的场景；
 * 流式方法用于真实回复和首包探测场景，返回 Disposable 让调用方可以取消正在进行的流。</p>
 */
public interface LLMService {

    // 旧同步路由入口已停用：当前 Agent runtime 统一走流式路由，
    // 由 streamDecisionWithRouting 收集完整 ChatResponse，或由 streamChat 直接推送最终回答。
    // ChatResponse chatWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools);

    /** 流式决策调用：内部先走流式路由，再收集成 BufferedStreamingResponse 返回。deepThinking 默认 false。 */
    default BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt, String systemPrompt, List<ToolCallback> tools) {
        return streamDecisionWithRouting(prompt, systemPrompt, tools, false);
    }

    /** 带外部回调的流式决策调用，collector 和外部 callback 会同时收到流式事件。deepThinking 默认 false。 */
    default BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt,
                                                                String systemPrompt,
                                                                List<ToolCallback> tools,
                                                                StreamCallback callback) {
        return streamDecisionWithRouting(prompt, systemPrompt, tools, callback, false);
    }

    /** 流式决策调用，显式指定 deepThinking。DeepThink 的 planner/reflection/verification 阶段使用。 */
    BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt, String systemPrompt,
                                                        List<ToolCallback> tools, boolean deepThinking);

    /** 带外部回调的流式决策调用，显式指定 deepThinking。 */
    BufferedStreamingResponse streamDecisionWithRouting(Prompt prompt, String systemPrompt,
                                                        List<ToolCallback> tools,
                                                        StreamCallback callback, boolean deepThinking);

    /** 真实流式聊天入口；返回的 Disposable 可用于首包超时、用户取消等场景。 */
    Disposable streamChat(Prompt prompt, boolean deepThinking, StreamCallback callback);
}
