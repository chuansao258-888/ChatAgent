package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.chat.routing.StreamCallback;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.sse.SseService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.Assert;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 消息桥接器：把 Agent 运行时产出的消息落库，并通过 SSE 推给前端。
 *
 * <p>这个类处在 Agent 与会话展示层之间，不负责模型路由本身；
 * 它负责把 LLM 流式回调转换成前端能理解的 AI_GENERATED_CONTENT、AI_THINKING、
 * AI_DONE、TURN_ROLLBACK 等事件。</p>
 */
@Component
@Slf4j
public class AgentMessageBridgeImpl implements AgentMessageBridge {

    // SSE 推送服务：所有实时消息最终都从这里发到浏览器订阅端。
    private final SseService sseService;
    // DTO -> VO 转换器：落库用 DTO，推给前端用 VO。
    private final ChatMessageConverter chatMessageConverter;
    // 会话消息门面：负责创建、更新、删除聊天消息。
    private final ChatMessageFacadeService chatMessageFacadeService;
    // 当前 turn 的引用来源缓存：RAG 检索出的 citations 会在最终 assistant 消息上消费。
    private final CurrentTurnCitationHolder currentTurnCitationHolder;
    // 读取路由流式总超时/首包超时配置，用于等待流结束时兜底取消。
    private final ChatRoutingProperties routingProperties;
    // Micrometer 指标注册器可能不存在；为空时 recordTimer 会退化成 no-op。
    private final MeterRegistry meterRegistry;

    public AgentMessageBridgeImpl(SseService sseService,
                                  ChatMessageConverter chatMessageConverter,
                                  ChatMessageFacadeService chatMessageFacadeService,
                                  CurrentTurnCitationHolder currentTurnCitationHolder,
                                  ChatRoutingProperties routingProperties,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.sseService = sseService;
        this.chatMessageConverter = chatMessageConverter;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.currentTurnCitationHolder = currentTurnCitationHolder;
        this.routingProperties = routingProperties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Override
    public void persistAndPublish(String chatSessionId, String turnId, Message message) {
        // AssistantMessage 是模型生成的助手消息：落一条 ASSISTANT 消息并推给前端。
        if (message instanceof AssistantMessage assistantMessage) {
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            // 如果这条 assistant 消息包含 tool calls，它只是工具调用决策，不消费 citations；
            // citations 应该留给后续真正的最终回答消息。
            List<CitationMetadata> citations = (toolCalls == null || toolCalls.isEmpty())
                    ? currentTurnCitationHolder.take(chatSessionId, turnId)
                    : List.of();
            ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(chatSessionId)
                    .turnId(turnId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(toolCalls)
                            .citations(citations.isEmpty() ? null : citations)
                            .build())
                    .build();
            send(chatMessageDTO);
            return;
        }

        // ToolResponseMessage 可能包含多个工具返回；每个工具返回单独落一条 TOOL 消息。
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                        .role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(chatSessionId)
                        .turnId(turnId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                send(chatMessageDTO);
            }
            return;
        }

        throw new IllegalArgumentException("Unsupported message type: " + message.getClass().getName());
    }

    @Override
    public String streamFinalResponse(String chatSessionId, String turnId, Prompt prompt, LLMService llmService, boolean deepThinking) {
        // 最终回复阶段：先创建一条空 assistant 消息，拿到 messageId。
        // 后续每个 content chunk 都基于这个 messageId 推送快照，前端才能增量更新同一条消息。
        List<CitationMetadata> citations = currentTurnCitationHolder.take(chatSessionId, turnId);
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .sessionId(chatSessionId)
                .turnId(turnId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .citations(citations.isEmpty() ? null : citations)
                        .build())
                .build();
        CreateChatMessageResponse created = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(created.getChatMessageId());
        chatMessageDTO.setTurnSeq(created.getTurnSeq());

        ChatMessageVO baseVo = chatMessageConverter.toVO(chatMessageDTO);

        // fullContent/fullThinking 分别累积正文和思考过程；回调线程会不断追加。
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        // streamLatch 用来让当前 Agent loop 等到流式回复 complete/error 后再继续。
        CountDownLatch streamLatch = new CountDownLatch(1);
        // 保存 routeAndStream 返回的 Disposable，超时或中断时可以取消上游模型流。
        AtomicReference<Disposable> streamHandle = new AtomicReference<>();
        // terminal 确保 complete/error 只处理一次，避免重复落库/重复 DONE。
        AtomicBoolean terminal = new AtomicBoolean(false);
        // StringBuilder 不是线程安全的；内容追加、快照构造、最终读取都用同一把锁保护。
        Object contentLock = new Object();
        long streamStartMs = System.currentTimeMillis();
        // 记录首个正文 chunk 到达时间，用于统计浏览器侧可感知的首字延迟。
        AtomicLong firstContentTimeMs = new AtomicLong(0);
        AtomicBoolean firstContentRecorded = new AtomicBoolean(false);

        StreamCallback sseAdapter = new StreamCallback() {
            @Override
            public void onContent(String content) {
                // complete/error 后到达的迟到 chunk 直接忽略，防止污染已结束消息。
                if (terminal.get()) return;
                if (firstContentRecorded.compareAndSet(false, true)) {
                    firstContentTimeMs.set(System.currentTimeMillis());
                }
                ChatMessageVO snapshot;
                synchronized (contentLock) {
                    fullContent.append(content);
                    // 每次都生成完整内容快照，而不是只推增量，方便前端直接覆盖渲染。
                    snapshot = snapshotMessage(baseVo, fullContent.toString());
                }

                // AI_GENERATED_CONTENT 表示助手正文更新。
                SseMessage msg = new SseMessage(
                    SseMessage.Type.AI_GENERATED_CONTENT,
                    SseMessage.Payload.builder().message(snapshot).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, msg);
            }

            @Override
            public void onThinking(String content) {
                // thinking 只用于前端展示“正在思考”，不写入最终 content 字段。
                if (terminal.get()) return;
                String thinkingText;
                synchronized (contentLock) {
                    fullThinking.append(content);
                    thinkingText = fullThinking.toString();
                }
                // 前端通过 AI_THINKING 的 statusText 展示累计 thinking 文本。
                SseMessage msg = new SseMessage(
                    SseMessage.Type.AI_THINKING,
                    SseMessage.Payload.builder().statusText(thinkingText).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, msg);
            }

            @Override
            public void onComplete() {
                // 只有第一个终止事件可以进入；后续重复 complete/error 都会被挡住。
                if (!terminal.compareAndSet(false, true)) return;
                // 流正常结束后，把累计正文写回数据库，形成最终 assistant 消息内容。
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                synchronized (contentLock) {
                    updateReq.setContent(fullContent.toString());
                }
                // 当前实现只持久化正文；thinking 作为实时状态推送，不额外写入 metadata。
                chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);

                // 通知前端本条 assistant 消息流式输出结束。
                SseMessage msg = new SseMessage(
                    SseMessage.Type.AI_DONE,
                    SseMessage.Payload.builder().done(true).turnId(turnId).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, msg);
                streamLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                // 错误终止同样只处理一次；否则可能重复追加错误提示或重复 DONE。
                if (!terminal.compareAndSet(false, true)) return;
                log.error("Streaming response error", t);

                // 即使流中断，也保留已经收到的正文，并追加用户可读的中断提示。
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                String errorSuffix = "\n\n[系统提示：网络连接不稳定，回复已中断]";
                ChatMessageVO snapshot;
                synchronized (contentLock) {
                    String interruptedContent = fullContent + errorSuffix;
                    updateReq.setContent(interruptedContent);
                    snapshot = snapshotMessage(baseVo, interruptedContent);
                }
                chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);

                // 先推一条带中断提示的正文快照，让前端能显示已经生成的部分。
                SseMessage errorMsg = new SseMessage(
                    SseMessage.Type.AI_GENERATED_CONTENT,
                    SseMessage.Payload.builder().message(snapshot).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, errorMsg);
                // 再推 AI_ERROR，给前端状态栏或 toast 使用。
                publishError(chatSessionId, chatMessageDTO.getId(), "网络连接不稳定，回复已中断");

                // 最后仍然推 DONE，让前端关闭 loading 状态。
                SseMessage doneMsg = new SseMessage(
                    SseMessage.Type.AI_DONE,
                    SseMessage.Payload.builder().done(true).turnId(turnId).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, doneMsg);
                streamLatch.countDown();
            }
        };

        // 发起最终回答流式调用。streamChat 内部会进入 routeAndStream 做首包探测和候选 fallback。
        try {
            Disposable disposable = llmService.streamChat(prompt, deepThinking, sseAdapter);
            if (disposable == null) {
                sseAdapter.onError(new IllegalStateException("LLM stream returned a null disposable"));
            } else {
                streamHandle.set(disposable);
            }
        } catch (Exception e) {
            sseAdapter.onError(e);
        }

        // 等待当前流完整结束：Agent loop 需要最终文本，所以这里不能发起后立刻返回。
        try {
            long timeoutSeconds = Math.max(
                    routingProperties.getStreamTotalTimeoutSeconds(),
                    routingProperties.getFirstPacketTimeoutSeconds() + 5L);
            if (!streamLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                // 总超时说明上游没有正常 complete/error；取消模型流并走错误收尾。
                Disposable disposable = streamHandle.get();
                if (disposable != null) {
                    disposable.dispose();
                }
                sseAdapter.onError(new IllegalStateException("Streaming response timed out after " + timeoutSeconds + "s"));
            }
        } catch (InterruptedException e) {
            // 恢复中断标记，并取消上游流，避免后台请求继续占资源。
            Thread.currentThread().interrupt();
            Disposable disposable = streamHandle.get();
            if (disposable != null) {
                disposable.dispose();
            }
            sseAdapter.onError(e);
        }

        // 记录首个正文 chunk 延迟；如果完全没有正文，则不记录。
        if (firstContentTimeMs.get() > 0) {
            long firstByteLatencyMs = firstContentTimeMs.get() - streamStartMs;
            recordTimer("chatagent.sse.first_byte.latency", firstByteLatencyMs, "session", chatSessionId);
        }

        synchronized (contentLock) {
            return fullContent.toString();
        }
    }

    @Override
    public BufferedStreamingResponse streamDecisionResponse(String chatSessionId,
                                                           String turnId,
                                                           Prompt prompt,
                                                           String systemPrompt,
                                                           List<ToolCallback> tools,
                                                           LLMService llmService) {
        // 决策阶段：先创建一条 provisional assistant 消息。
        // 如果模型最终没有 tool calls，这条消息就是最终回答；如果有 tool calls，后面会 rollback 删除它。
        List<CitationMetadata> citations = currentTurnCitationHolder.peek(chatSessionId, turnId);
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .sessionId(chatSessionId)
                .turnId(turnId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .citations(citations.isEmpty() ? null : citations)
                        .build())
                .build();
        CreateChatMessageResponse created = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(created.getChatMessageId());
        chatMessageDTO.setTurnSeq(created.getTurnSeq());

        ChatMessageVO baseVo = chatMessageConverter.toVO(chatMessageDTO);
        // 决策流也会实时推给前端；同时 streamDecisionWithRouting 内部会收集完整 BufferedStreamingResponse。
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        Object contentLock = new Object();

        // streamDecisionWithRouting 会一边通过 callback 透传流式内容，一边返回完整 ChatResponse。
        BufferedStreamingResponse bufferedResponse = llmService.streamDecisionWithRouting(
                prompt,
                systemPrompt,
                tools,
                new StreamCallback() {
                    @Override
                    public void onContent(String content) {
                        if (content == null || content.isEmpty()) {
                            return;
                        }
                        ChatMessageVO snapshot;
                        synchronized (contentLock) {
                            fullContent.append(content);
                            // 这里先把决策阶段文本当成临时 assistant 消息播出去，提高前端响应速度。
                            snapshot = snapshotMessage(baseVo, fullContent.toString());
                        }
                        publishContent(chatSessionId, chatMessageDTO.getId(), snapshot);
                    }

                    @Override
                    public void onThinking(String content) {
                        if (content == null || content.isEmpty()) {
                            return;
                        }
                        String thinkingText;
                        synchronized (contentLock) {
                            fullThinking.append(content);
                            thinkingText = fullThinking.toString();
                        }
                        // thinking 同样实时展示，但最终是否保留这条 assistant 消息取决于是否有 tool calls。
                        publishThinking(chatSessionId, chatMessageDTO.getId(), thinkingText);
                    }

                    @Override
                    public void onComplete() {
                        // 这里不立即 DONE：必须等 bufferedResponse 出来后，判断有没有 tool calls。
                        // 有 tool calls 要 rollback；没有 tool calls 才能 finalize 这条消息。
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("Decision stream failed before final branch resolution: sessionId={}, turnId={}, chatMessageId={}, error={}",
                                chatSessionId, turnId, chatMessageDTO.getId(), error.getMessage(), error);
                    }
                });

        // 下面这些断言是在保护 streamDecisionWithRouting 的契约：
        // 它必须返回完整 ChatResponse，Agent loop 才能判断是否进入工具调用分支。
        Assert.notNull(bufferedResponse, "Buffered streamed response cannot be null");
        Assert.notNull(bufferedResponse.response(), "Buffered streamed response cannot carry a null ChatResponse");
        Assert.notNull(bufferedResponse.response().getResult(), "Buffered streamed response cannot carry a null Generation result");

        AssistantMessage output = bufferedResponse.response().getResult().getOutput();
        Assert.notNull(output, "Buffered streamed response cannot carry a null AssistantMessage");

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 决策阶段如果产出了 tool calls，说明刚才播出去的文字只是临时内容。
            // 删除 provisional assistant 消息，并通知前端回滚这个 turn 的临时展示。
            log.info("Rolling back provisional streamed assistant message because tool calls were emitted: sessionId={}, turnId={}, chatMessageId={}, toolCallCount={}",
                    chatSessionId, turnId, chatMessageDTO.getId(), toolCalls.size());
            chatMessageFacadeService.deleteChatMessage(chatMessageDTO.getId());
            publishTurnRollback(chatSessionId, turnId);
            return bufferedResponse;
        }

        String finalContent;
        ChatMessageVO finalSnapshot;
        synchronized (contentLock) {
            // 没有 tool calls 时，这次决策流就是最终回答。
            // 优先使用 bufferedResponse 中的最终文本；如果最终文本缺失或比已播内容短，就保留已累计内容。
            finalContent = output.getText();
            if (finalContent == null || finalContent.length() < fullContent.length()) {
                finalContent = fullContent.toString();
            } else if (finalContent.length() > fullContent.length()) {
                fullContent.setLength(0);
                fullContent.append(finalContent);
            }
            finalSnapshot = snapshotMessage(baseVo, finalContent);
        }

        UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
        updateReq.setContent(finalContent);
        chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);
        // 再推一次最终快照，确保前端与数据库里的最终内容一致。
        publishContent(chatSessionId, chatMessageDTO.getId(), finalSnapshot);
        // 现在确定这条 assistant 消息保留，才真正消费 citations。
        currentTurnCitationHolder.take(chatSessionId, turnId);
        publishDone(chatSessionId, chatMessageDTO.getId(), turnId);
        return bufferedResponse;
    }

    // ========== 内部决策收集（Phase 3: DeepThink 内部调用路径） ==========

    @Override
    public BufferedStreamingResponse collectDecisionResponse(String chatSessionId,
                                                              String turnId,
                                                              Prompt prompt,
                                                              String systemPrompt,
                                                              List<ToolCallback> tools,
                                                              LLMService llmService,
                                                              DecisionVisibility visibility,
                                                              boolean deepThinking,
                                                              String deepThinkPhase,
                                                              String planStepId) {
        // USER_VISIBLE_PROVISIONAL 走现有 streamDecisionResponse 路径，完全向后兼容。
        if (visibility == DecisionVisibility.USER_VISIBLE_PROVISIONAL) {
            return streamDecisionResponse(chatSessionId, turnId, prompt, systemPrompt, tools, llmService);
        }

        // === INTERNAL_TRACE_ONLY 路径 ===
        // 不创建 provisional 消息、不流式推前端、tool_call/tool_response 持久化为 internal 消息。
        log.info("Collecting internal decision: sessionId={}, turnId={}, phase={}, stepId={}, deepThinking={}",
                chatSessionId, turnId, deepThinkPhase, planStepId, deepThinking);

        // 内部决策不需要外部 callback，流式事件只由 RoutingLLMService 内部 collector 收集。
        BufferedStreamingResponse bufferedResponse = llmService.streamDecisionWithRouting(
                prompt, systemPrompt, tools, deepThinking);

        Assert.notNull(bufferedResponse, "Buffered streamed response cannot be null");
        Assert.notNull(bufferedResponse.response(), "Buffered streamed response cannot carry a null ChatResponse");
        Assert.notNull(bufferedResponse.response().getResult(), "Buffered streamed response cannot carry a null Generation result");

        AssistantMessage output = bufferedResponse.response().getResult().getOutput();
        Assert.notNull(output, "Buffered streamed response cannot carry a null AssistantMessage");

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 持久化 internal assistant tool_call 消息
            persistInternalAssistant(chatSessionId, turnId, output, deepThinkPhase, planStepId);
            log.info("Persisted internal assistant with {} tool calls: sessionId={}, turnId={}, phase={}",
                    toolCalls.size(), chatSessionId, turnId, deepThinkPhase);
        }
        // 非 tool_call 的响应不持久化——调用方从 BufferedStreamingResponse 获取文本。

        return bufferedResponse;
    }

    @Override
    public void publishStatusEvent(String chatSessionId, String turnId, SseMessage.Type type, String statusText) {
        // status-only SSE：只有 statusText + turnId，没有 message 和 metadata。
        sseService.publish(chatSessionId, SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
                        .turnId(turnId)
                        .build())
                .build());
    }

    /**
     * 持久化 internal assistant 消息（含 tool calls），不推送 SSE。
     */
    private void persistInternalAssistant(String chatSessionId, String turnId,
                                           AssistantMessage output,
                                           String deepThinkPhase, String planStepId) {
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(output.getText())
                .sessionId(chatSessionId)
                .turnId(turnId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(output.getToolCalls())
                        .internal(true)
                        .deepThinkPhase(deepThinkPhase)
                        .planStepId(planStepId)
                        .build())
                .build();
        chatMessageFacadeService.createChatMessage(dto);
    }

    /**
     * 持久化 internal tool response 消息，不推送 SSE。
     * DeepThink 引擎执行工具后可调用此方法记录工具响应。
     */
    @Override
    public void persistInternalToolResponses(String chatSessionId, String turnId,
                                              ToolResponseMessage toolResponseMessage,
                                              String deepThinkPhase, String planStepId) {
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
            ChatMessageDTO dto = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.TOOL)
                    .content(toolResponse.responseData())
                    .sessionId(chatSessionId)
                    .turnId(turnId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolResponse(toolResponse)
                            .internal(true)
                            .deepThinkPhase(deepThinkPhase)
                            .planStepId(planStepId)
                            .build())
                    .build();
            chatMessageFacadeService.createChatMessage(dto);
        }
    }

    // 当前生产流程没有调用 publishBufferedFinalResponse：
    // streamDecisionResponse 自己已经处理了“决策流无 tool calls 就作为最终回答”的分支；
    // 决策流有 tool calls 时会回滚临时消息，然后工具执行结束后走 streamFinalResponse。
    // 之前这里曾保留一套“把 BufferedStreamingResponse 重新创建消息并回放 SSE”的备用实现，
    // 但它不在当前 Agent loop 调用链上，容易干扰阅读主线，所以先注释掉。

    /**
     * 持久化一条规范化聊天消息，并把转换后的 VO 通过 SSE 推给前端。

     *
     * @param chatMessageDTO 规范化聊天消息 DTO
     */
    private void send(ChatMessageDTO chatMessageDTO) {
        CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(chatMessage.getChatMessageId());
        chatMessageDTO.setTurnSeq(chatMessage.getTurnSeq());

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
        sseService.publish(chatMessageDTO.getSessionId(), sseMessage);
    }

    private void publishContent(String chatSessionId, String chatMessageId, ChatMessageVO message) {
        // 推送助手正文快照。前端通常按 chatMessageId 找到同一条消息并覆盖内容。
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_GENERATED_CONTENT,
                SseMessage.Payload.builder().message(message).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishThinking(String chatSessionId, String chatMessageId, String thinkingText) {
        // 推送 thinking/status 文本，不直接写入 ChatMessageVO.content。
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_THINKING,
                SseMessage.Payload.builder().statusText(thinkingText).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishDone(String chatSessionId, String chatMessageId, String turnId) {
        // 通知前端该 assistant 消息完成，关闭 loading/打字状态。
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_DONE,
                SseMessage.Payload.builder().done(true).turnId(turnId).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishError(String chatSessionId, String chatMessageId, String statusText) {
        // 推送错误状态；具体已生成内容通常会通过 publishContent 另行发送。
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_ERROR,
                SseMessage.Payload.builder().statusText(statusText).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishTurnRollback(String chatSessionId, String turnId) {
        // 决策阶段发现 tool calls 时，前端需要撤销刚才临时展示的 assistant 消息。
        sseService.publish(chatSessionId, SseMessage.builder()
                .type(SseMessage.Type.TURN_ROLLBACK)
                .payload(SseMessage.Payload.builder()
                        .turnId(turnId)
                        .build())
                .build());
    }

    private ChatMessageVO snapshotMessage(ChatMessageVO baseVo, String content) {
        // 基于最初创建的 VO 克隆一份快照，只替换 content。
        // 这样 id/session/turn/metadata/seqNo 保持稳定，前端能持续更新同一条消息。
        return ChatMessageVO.builder()
                .id(baseVo.getId())
                .sessionId(baseVo.getSessionId())
                .turnId(baseVo.getTurnId())
                .turnSeq(baseVo.getTurnSeq())
                .role(baseVo.getRole())
                .content(content)
                .metadata(baseVo.getMetadata())
                .seqNo(baseVo.getSeqNo())
                .build();
    }

    private void recordTimer(String name, long durationMs, String... tags) {
        // 指标系统不是主链路依赖：没有 meterRegistry 或记录失败都不影响聊天流程。
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.timer(name, tags).record(Math.max(durationMs, 0L), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to record SSE timer: name={}, error={}", name, e.getMessage());
        }
    }

    // ========== Trace Metadata 附加（Phase 4 Fix 2） ==========

    @Override
    public void attachTraceMetadata(String chatSessionId, String turnId, com.yulong.chatagent.support.dto.AgentTraceMetadata trace) {
        List<ChatMessageDTO> recentMessages = chatMessageFacadeService
                .getChatMessagesBySessionIdRecently(chatSessionId, 50);

        // 从最新消息向前查找指定 turn 的非 internal assistant 消息
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ChatMessageDTO msg = recentMessages.get(i);
            if (turnId.equals(msg.getTurnId())
                    && msg.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                    && !(msg.getMetadata() != null && Boolean.TRUE.equals(msg.getMetadata().getInternal()))) {
                ChatMessageDTO.MetaData meta = msg.getMetadata();
                if (meta == null) {
                    meta = ChatMessageDTO.MetaData.builder().build();
                }
                meta.setAgentTrace(trace);
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                updateReq.setMetadata(meta);
                chatMessageFacadeService.updateChatMessage(msg.getId(), updateReq);
                log.info("Attached trace metadata to message {} for turn {}", msg.getId(), turnId);
                return;
            }
        }
        log.warn("No non-internal assistant message found for turn {} to attach trace", turnId);
    }
}
