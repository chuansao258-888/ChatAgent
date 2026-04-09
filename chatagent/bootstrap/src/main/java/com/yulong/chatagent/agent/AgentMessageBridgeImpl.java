package com.yulong.chatagent.agent;

import com.yulong.chatagent.agent.runtime.CurrentTurnCitationHolder;
import com.yulong.chatagent.chat.routing.BufferedStreamingResponse;
import com.yulong.chatagent.chat.routing.LLMService;
import com.yulong.chatagent.chat.routing.ChatRoutingProperties;
import com.yulong.chatagent.chat.routing.StreamCallback;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.conversation.converter.ChatMessageConverter;
import com.yulong.chatagent.rag.model.CitationMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default bridge that stores agent output as chat messages and forwards it over SSE.
 */
@Component
@Slf4j
public class AgentMessageBridgeImpl implements AgentMessageBridge {

    private final SseService sseService;
    private final ChatMessageConverter chatMessageConverter;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final CurrentTurnCitationHolder currentTurnCitationHolder;
    private final ChatRoutingProperties routingProperties;

    public AgentMessageBridgeImpl(SseService sseService,
                                  ChatMessageConverter chatMessageConverter,
                                  ChatMessageFacadeService chatMessageFacadeService,
                                  CurrentTurnCitationHolder currentTurnCitationHolder,
                                  ChatRoutingProperties routingProperties) {
        this.sseService = sseService;
        this.chatMessageConverter = chatMessageConverter;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.currentTurnCitationHolder = currentTurnCitationHolder;
        this.routingProperties = routingProperties;
    }

    @Override
    public void persistAndPublish(String chatSessionId, String turnId, Message message) {
        // Assistant output becomes one persisted assistant message.
        if (message instanceof AssistantMessage assistantMessage) {
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
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

        // Tool output becomes one persisted tool message per tool response.
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
    public String streamFinalResponse(String chatSessionId, String turnId, Prompt prompt, LLMService llmService) {
        // 1. Prepare an empty persisted message to get an ID for streaming
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

        ChatMessageVO baseVo = chatMessageConverter.toVO(chatMessageDTO);

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        CountDownLatch streamLatch = new CountDownLatch(1);
        AtomicReference<Disposable> streamHandle = new AtomicReference<>();
        AtomicBoolean terminal = new AtomicBoolean(false);
        Object contentLock = new Object();

        StreamCallback sseAdapter = new StreamCallback() {
            @Override
            public void onContent(String content) {
                if (terminal.get()) return;
                ChatMessageVO snapshot;
                synchronized (contentLock) {
                    fullContent.append(content);
                    snapshot = snapshotMessage(baseVo, fullContent.toString());
                }

                SseMessage msg = new SseMessage(
                    SseMessage.Type.AI_GENERATED_CONTENT,
                    SseMessage.Payload.builder().message(snapshot).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, msg);
            }

            @Override
            public void onThinking(String content) {
                if (terminal.get()) return;
                String thinkingText;
                synchronized (contentLock) {
                    fullThinking.append(content);
                    thinkingText = fullThinking.toString();
                }
                // Thinking is mapped to statusText in the frontend DTO for AI_THINKING
                SseMessage msg = new SseMessage(
                    SseMessage.Type.AI_THINKING,
                    SseMessage.Payload.builder().statusText(thinkingText).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, msg);
            }

            @Override
            public void onComplete() {
                if (!terminal.compareAndSet(false, true)) return;
                // Update final message in DB
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                synchronized (contentLock) {
                    updateReq.setContent(fullContent.toString());
                }
                // We should also store thinking in metadata if needed, but keeping it simple.
                chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);

                // Publish DONE
                SseMessage msg = new SseMessage(
                    SseMessage.Type.AI_DONE,
                    SseMessage.Payload.builder().done(true).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, msg);
                streamLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                if (!terminal.compareAndSet(false, true)) return;
                log.error("Streaming response error", t);

                // Still update what we got so far
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                String errorSuffix = "\n\n[系统提示：网络连接不稳定，回复已中断]";
                ChatMessageVO snapshot;
                synchronized (contentLock) {
                    String interruptedContent = fullContent + errorSuffix;
                    updateReq.setContent(interruptedContent);
                    snapshot = snapshotMessage(baseVo, interruptedContent);
                }
                chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);

                SseMessage errorMsg = new SseMessage(
                    SseMessage.Type.AI_GENERATED_CONTENT,
                    SseMessage.Payload.builder().message(snapshot).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, errorMsg);
                publishError(chatSessionId, chatMessageDTO.getId(), "网络连接不稳定，回复已中断");

                SseMessage doneMsg = new SseMessage(
                    SseMessage.Type.AI_DONE,
                    SseMessage.Payload.builder().done(true).build(),
                    SseMessage.Metadata.builder().chatMessageId(chatMessageDTO.getId()).build()
                );
                sseService.publish(chatSessionId, doneMsg);
                streamLatch.countDown();
            }
        };

        // Launch stream
        try {
            Disposable disposable = llmService.streamChat(prompt, false, sseAdapter);
            if (disposable == null) {
                sseAdapter.onError(new IllegalStateException("LLM stream returned a null disposable"));
            } else {
                streamHandle.set(disposable);
            }
        } catch (Exception e) {
            sseAdapter.onError(e);
        }

        // Wait for completion (so the Agent loop doesn't proceed until the stream finishes)
        try {
            long timeoutSeconds = Math.max(
                    routingProperties.getStreamTotalTimeoutSeconds(),
                    routingProperties.getFirstPacketTimeoutSeconds() + 5L);
            if (!streamLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                Disposable disposable = streamHandle.get();
                if (disposable != null) {
                    disposable.dispose();
                }
                sseAdapter.onError(new IllegalStateException("Streaming response timed out after " + timeoutSeconds + "s"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Disposable disposable = streamHandle.get();
            if (disposable != null) {
                disposable.dispose();
            }
            sseAdapter.onError(e);
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

        ChatMessageVO baseVo = chatMessageConverter.toVO(chatMessageDTO);
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        Object contentLock = new Object();

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
                        publishThinking(chatSessionId, chatMessageDTO.getId(), thinkingText);
                    }

                    @Override
                    public void onComplete() {
                        // Finalize after the buffered response is materialized so we can branch on tool calls.
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("Decision stream failed before final branch resolution: sessionId={}, turnId={}, chatMessageId={}, error={}",
                                chatSessionId, turnId, chatMessageDTO.getId(), error.getMessage(), error);
                    }
                });

        Assert.notNull(bufferedResponse, "Buffered streamed response cannot be null");
        Assert.notNull(bufferedResponse.response(), "Buffered streamed response cannot carry a null ChatResponse");
        Assert.notNull(bufferedResponse.response().getResult(), "Buffered streamed response cannot carry a null Generation result");

        AssistantMessage output = bufferedResponse.response().getResult().getOutput();
        Assert.notNull(output, "Buffered streamed response cannot carry a null AssistantMessage");

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            log.info("Rolling back provisional streamed assistant message because tool calls were emitted: sessionId={}, turnId={}, chatMessageId={}, toolCallCount={}",
                    chatSessionId, turnId, chatMessageDTO.getId(), toolCalls.size());
            chatMessageFacadeService.deleteChatMessage(chatMessageDTO.getId());
            publishTurnRollback(chatSessionId, turnId);
            return bufferedResponse;
        }

        String finalContent;
        ChatMessageVO finalSnapshot;
        synchronized (contentLock) {
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
        publishContent(chatSessionId, chatMessageDTO.getId(), finalSnapshot);
        currentTurnCitationHolder.take(chatSessionId, turnId);
        publishDone(chatSessionId, chatMessageDTO.getId());
        return bufferedResponse;
    }

    @Override
    public void publishBufferedFinalResponse(String chatSessionId, String turnId, BufferedStreamingResponse bufferedResponse) {
        if (bufferedResponse == null || bufferedResponse.response() == null || bufferedResponse.response().getResult() == null) {
            throw new IllegalArgumentException("Buffered streamed response cannot be null");
        }

        List<CitationMetadata> citations = currentTurnCitationHolder.take(chatSessionId, turnId);
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .sessionId(chatSessionId)
                .turnId(turnId)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(bufferedResponse.response().hasToolCalls()
                                ? bufferedResponse.response().getResult().getOutput().getToolCalls()
                                : null)
                        .citations(citations.isEmpty() ? null : citations)
                        .build())
                .build();
        CreateChatMessageResponse created = chatMessageFacadeService.createChatMessage(chatMessageDTO);
        chatMessageDTO.setId(created.getChatMessageId());

        ChatMessageVO baseVo = chatMessageConverter.toVO(chatMessageDTO);
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();

        for (BufferedStreamingResponse.BufferedStreamEvent event : bufferedResponse.events()) {
            if (event == null || event.text() == null || event.text().isEmpty()) {
                continue;
            }
            if (event.type() == BufferedStreamingResponse.EventType.THINKING) {
                fullThinking.append(event.text());
                publishThinking(chatSessionId, chatMessageDTO.getId(), fullThinking.toString());
                continue;
            }
            fullContent.append(event.text());
            publishContent(chatSessionId, chatMessageDTO.getId(), snapshotMessage(baseVo, fullContent.toString()));
        }

        String finalContent = bufferedResponse.response().getResult().getOutput().getText();
        if (finalContent != null && finalContent.length() >= fullContent.length()) {
            fullContent.setLength(0);
            fullContent.append(finalContent);
        }

        UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
        updateReq.setContent(fullContent.toString());
        chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);
        publishDone(chatSessionId, chatMessageDTO.getId());
    }

    /**
     * Persists a normalized chat message and publishes the resulting view model through SSE.

     *
     * @param chatMessageDTO normalized chat message DTO
     */
    private void send(ChatMessageDTO chatMessageDTO) {
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
        sseService.publish(chatMessageDTO.getSessionId(), sseMessage);
    }

    private void publishContent(String chatSessionId, String chatMessageId, ChatMessageVO message) {
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_GENERATED_CONTENT,
                SseMessage.Payload.builder().message(message).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishThinking(String chatSessionId, String chatMessageId, String thinkingText) {
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_THINKING,
                SseMessage.Payload.builder().statusText(thinkingText).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishDone(String chatSessionId, String chatMessageId) {
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_DONE,
                SseMessage.Payload.builder().done(true).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishError(String chatSessionId, String chatMessageId, String statusText) {
        sseService.publish(chatSessionId, new SseMessage(
                SseMessage.Type.AI_ERROR,
                SseMessage.Payload.builder().statusText(statusText).build(),
                SseMessage.Metadata.builder().chatMessageId(chatMessageId).build()
        ));
    }

    private void publishTurnRollback(String chatSessionId, String turnId) {
        sseService.publish(chatSessionId, SseMessage.builder()
                .type(SseMessage.Type.TURN_ROLLBACK)
                .payload(SseMessage.Payload.builder()
                        .turnId(turnId)
                        .build())
                .build());
    }

    private ChatMessageVO snapshotMessage(ChatMessageVO baseVo, String content) {
        return ChatMessageVO.builder()
                .id(baseVo.getId())
                .sessionId(baseVo.getSessionId())
                .turnId(baseVo.getTurnId())
                .role(baseVo.getRole())
                .content(content)
                .metadata(baseVo.getMetadata())
                .seqNo(baseVo.getSeqNo())
                .build();
    }
}
