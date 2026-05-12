package com.yulong.chatagent.conversation.event;

import com.yulong.chatagent.agent.AgentRunException;
import com.yulong.chatagent.agent.AgentRunResult;
import com.yulong.chatagent.agent.ChatAgent;
import com.yulong.chatagent.agent.ChatAgentFactory;
import com.yulong.chatagent.agent.runtime.CurrentIntentResolutionHolder;
import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.support.chat.ChatModelAvailability;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.metrics.ChatTurnMetricRecorder;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 真正执行一轮 ChatEvent 的处理器。
 * <p>
 * 本类被本地 @Async 监听器和 MQ consumer 复用，是 conversation 层进入
 * Agent runtime 的统一入口。
 */
@Slf4j
@Component
@AllArgsConstructor
public class ChatEventProcessor {

    private final ChatAgentFactory chatAgentFactory;
    private final ChatModelAvailability chatModelAvailability;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final ConversationTurnCompletionPublisher conversationTurnCompletionPublisher;
    private final SseService sseService;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;
    private final ChatTurnMetricRecorder chatTurnMetricRecorder;
    private final ChatSessionRepository chatSessionRepository;

    public void process(ChatEvent event) {
        try {
            // 这里开始已经脱离用户请求线程，必须从事件中恢复本轮运行所需的全部上下文。
            log.info("Dispatching chat event: agentId={}, sessionId={}, userMessageId={}, recentHistorySize={}, intentKind={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), event.getRecentHistorySize(),
                    event.getIntentResolution() == null ? "DEFAULT" : event.getIntentResolution().kind());

            if (!chatModelAvailability.hasConfiguredProvider()) {
                // 本地开发环境可能没有配置模型 API key；直接给用户可见的降级说明，而不是静默失败。
                log.warn("No chat model provider configured: sessionId={}, userMessageId={}",
                        event.getSessionId(), event.getChatMessageId());
                publishAssistantMessage(
                        event,
                        """
                                ChatAgent is running without a configured chat model.
                                I received your message: %s

                                To enable real AI responses, set either CHATAGENT_DEEPSEEK_API_KEY or CHATAGENT_ZHIPUAI_API_KEY and restart the backend.
                                """.formatted(event.getUserInput())
                );
                recordMetricQuietly(
                        event,
                        new AgentRunResult(AgentRunResult.Status.ERROR, 0L, "MODEL_NOT_CONFIGURED", true)
                );
                conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
                return;
            }

            // 意图边界既会参与工具筛选，也会被 RAG 工具读取以限制检索范围。
            CurrentIntentResolutionHolder.set(event.getIntentResolution());
            // 创建一次性 ChatAgent 实例，随后进入 ReAct 主循环。
            ChatAgent chatAgent = chatAgentFactory.create(
                    event.getAgentId(),
                    event.getSessionId(),
                    event.getTurnId(),
                    event.getIntentResolution(),
                    event.getRewrittenInput(),
                    resolveUserId(event)
            );
            // 到这里 conversation 编排层的职责基本结束，控制权正式交给 Agent runtime。
            AgentRunResult runResult = chatAgent.run();
            recordMetricQuietly(event, runResult);
            conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
        } finally {
            // citations 不使用 ThreadLocal，而是按 session+turn 暂存；无论成功失败都要清理。
            currentTurnCitationHolder.clear(event.getSessionId(), event.getTurnId());
            currentTurnCitationHolder.clearBySession(event.getSessionId());
            CurrentIntentResolutionHolder.clear();
        }
    }

    /**
     * 删除某个 turn 下已经生成的 assistant/tool 消息。
     * <p>
     * MQ 重试或 DLQ replay 前会调用这里，避免同一轮输出重复落库、重复展示。
     */
    public void rollbackTurn(String sessionId, String turnId) {
        log.info("Rolling back chat turn output: sessionId={}, turnId={}", sessionId, turnId);
        // 这里删的是 assistant/tool 输出，不会删除用户消息本身。
        // 因为重试的语义是“重新生成这一轮的回答”，不是撤销用户的问题。
        chatMessageFacadeService.deleteAssistantAndToolMessagesForTurn(sessionId, turnId);

        // Notify the frontend to clear this turn's messages from the UI state
        sseService.publish(sessionId, SseMessage.builder()
                .type(SseMessage.Type.TURN_ROLLBACK)
                .payload(SseMessage.Payload.builder()
                        .turnId(turnId)
                        .build())
                .build());
    }

    /**
     * 异步 Agent 失败时补发一条用户可见的失败消息。
     * <p>
     * 这样即使后台任务重试耗尽，前端也能收到 AI_ERROR/AI_DONE，loading 状态不会卡住。
     */
    public void publishFailure(ChatEvent event, Exception ex) {
        try {
            recordMetricQuietly(event, resolveFailureMetric(ex));

            // 先回滚可能已经流式展示或落库的局部输出，再补发统一错误消息。
            rollbackTurn(event.getSessionId(), event.getTurnId());

            publishAssistantMessage(event, "Sorry, the agent failed to process this request. Please try again.");
            conversationTurnCompletionPublisher.publishCompletedTurn(event.getSessionId(), event.getTurnId());
        } catch (Exception failureHandlingException) {
            log.error("Failed to publish fallback error message: agentId={}, sessionId={}, userMessageId={}",
                    event.getAgentId(), event.getSessionId(), event.getChatMessageId(), failureHandlingException);
        }
    }

    private void publishAssistantMessage(ChatEvent event, String content) {
        // 这个方法用于模型不可用或异常兜底，不走 AgentMessageBridge 的流式路径。
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .sessionId(event.getSessionId())
                .turnId(event.getTurnId())
                .turnSeq(event.getTurnSeq())
                .content(content)
                .build();
        CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(chatMessage.getChatMessageId());

        ChatMessageVO chatMessageVO = chatMessageConverter.toVO(chatMessageDTO);
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(chatMessageVO)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .build())
                .build();
        // 失败补偿也沿用统一的 SSE 协议：
        // 先给一条 assistant 消息，再发送 AI_ERROR 和 AI_DONE，让前端能结束 loading。
        sseService.publish(event.getSessionId(), sseMessage);
        sseService.publish(event.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_ERROR)
                .payload(SseMessage.Payload.builder()
                        .statusText("Agent failed to process this request. Please try again.")
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .build())
                .build());
        sseService.publish(event.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_DONE)
                .payload(SseMessage.Payload.builder()
                        .done(true)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(chatMessageDTO.getId())
                        .build())
                .build());
    }

    private AgentRunResult resolveFailureMetric(Exception ex) {
        // AgentRunException 会携带已经分类好的运行结果；其他异常按通用规则再分类一次。
        if (ex instanceof AgentRunException agentRunException && agentRunException.getResult() != null) {
            return agentRunException.getResult();
        }
        return AgentRunResult.failure(0L, true, ex);
    }

    private void recordMetricQuietly(ChatEvent event, AgentRunResult runResult) {
        // 指标失败不能反过来影响聊天主链路，因此这里吞掉并记录 warning。
        try {
            chatTurnMetricRecorder.record(event, runResult);
        } catch (RuntimeException metricException) {
            log.warn("Failed to record chat turn metric: sessionId={}, turnId={}, error={}",
                    event == null ? null : event.getSessionId(),
                    event == null ? null : event.getTurnId(),
                    metricException.getMessage());
        }
    }

    private String resolveUserId(ChatEvent event) {
        if (event != null && org.springframework.util.StringUtils.hasText(event.getUserId())) {
            // 正常情况下 orchestrator 已经把 userId 放进事件里，直接复用即可。
            return event.getUserId();
        }
        if (event == null || !org.springframework.util.StringUtils.hasText(event.getSessionId())) {
            return null;
        }
        // 兜底场景（老消息、兼容路径）再回 session 表补查一次。
        ChatSessionDTO chatSession = chatSessionRepository.findById(event.getSessionId());
        return chatSession == null ? null : chatSession.getUserId();
    }
}
