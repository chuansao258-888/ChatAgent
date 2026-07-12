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
import com.yulong.chatagent.rag.model.RagSourceType;
import com.yulong.chatagent.sse.SseService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final String CURRENT_USER_REQUEST_MARKER = "Current user request:";
    private static final String NO_USER_ROLE_MESSAGE =
            "No user-role message is present in the current conversation history.";
    private static final int MAX_FINAL_REPAIR_ATTEMPTS = 2;
    private static final int MAX_ENGLISH_ANSWER_CJK_CHARS = 3;
    private static final Pattern REPEAT_REQUEST = Pattern.compile(
            "(?is)\\b(repeat|again|same|previous|last answer|what you said|restate|continue)\\b"
                    + "|重复|再说|刚才|上一"
    );
    private static final Pattern WORD = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern LATIN_WORD = Pattern.compile("\\b[A-Za-z]{2,}\\b");
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");
    private static final Pattern CITATION_REFERENCE = Pattern.compile("\\[(\\d{1,3})]");
    private static final Pattern DISTINCTIVE_EVIDENCE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Z][A-Z0-9]{1,}(?:-[A-Z0-9]+)+)(?![A-Za-z0-9])"
    );
    private static final Pattern NON_SUPPORTING_CITATION_CONTEXT = Pattern.compile(
            "(?is)\\b(?:no\\s+(?:evidence|results?|matches?|mention|reference)|neither|nor|not\\s+(?:appear|found|contain|cover|include|mention|reference)|"
                    + "does\\s+not\\s+(?:appear|contain|cover|include|mention|reference)|do\\s+not\\s+(?:appear|contain|cover|include|mention|reference)|"
                    + "did\\s+not\\s+(?:appear|contain|cover|include|mention|reference)|without\\s+(?:evidence|matches?|mention|reference)|unrelated|only\\s+cover)\\b"
    );
    private static final Pattern SESSION_FILE_CITATION_CONTEXT = Pattern.compile(
            "(?is)\\b(?:attachment|attached|uploaded|session\\s+(?:file|note|briefing)|file\\s+I\\s+just\\s+attached)\\b"
    );
    private static final Pattern USER_VISIBLE_TOOL_CALL_MARKUP = Pattern.compile(
            "(?is)<\\s*/?\\s*tool_call\\s*>"
                    + "|<\\s*tool_calls?\\b"
                    + "|\\{\\s*\"name\"\\s*:\\s*\"[A-Za-z0-9_]*(?:Tool|tool)[A-Za-z0-9_]*\"\\s*,\\s*\"arguments\"\\s*:"
    );
    private static final Set<String> GENERIC_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "what", "when", "where", "which", "who",
            "why", "how", "can", "could", "would", "should", "one", "two", "three", "make",
            "give", "tell", "need", "want", "please", "useful", "practical", "easier", "easy",
            "good", "better", "answer", "question", "request", "help", "thing", "way"
    );
    private static final Set<String> CITATION_EVIDENCE_STOP_WORDS = Set.of(
            "source", "sources", "chunk", "content", "document", "documents", "knowledge", "base",
            "session", "file", "files", "local", "public", "private", "marker", "status", "release"
    );
    private static final int MIN_CITATION_TERM_LENGTH = 5;
    private static final int MIN_CITATION_TERM_MATCHES_FOR_UNCITED_USAGE = 3;
    private static final Pattern NO_USER_CONTRADICTION = Pattern.compile(
            "(?is)(\\bI\\s+(?:do\\s+not|don't)\\s+(?:see|have|find)\\s+(?:a\\s+)?user\\s+(?:question|message|request)\\b"
                    + "|\\bI\\s+(?:do\\s+not|don't)\\s+(?:see|have|find)\\s+(?:a\\s+)?(?:specific\\s+)?(?:question|task|request)\\b"
                    + "|\\bI\\s+(?:do\\s+not|don't)\\s+(?:see|have|find)\\s+any\\s+user\\s+(?:questions?|messages?|requests?)\\b"
                    + "|\\bI\\s+(?:do\\s+not|don't)\\s+(?:see|have|find)\\s+any\\s+(?:previous\\s+)?messages?\\b"
                    + "|\\bthis\\s+(?:appears\\s+to\\s+be|is)\\s+the\\s+start\\s+of\\s+(?:our\\s+)?(?:conversation|interaction)\\b"
                    + "|\\bno\\s+user\\s+(?:question|message|request)\\b"
                    + "|\\bno\\s+(?:specific\\s+)?(?:question|task|request)\\b"
                    + "|\\bprovide\\s+(?:your\\s+)?(?:first\\s+)?(?:question|message|request)\\b"
                    + "|\\blet\\s+me\\s+know\\s+what\\s+you\\s+(?:need|would\\s+like).*?help\\s+with\\b"
                    + "|没有.*用户.*(?:问题|消息|请求)"
                    + "|用户.*(?:没有|未).*(?:问题|消息|请求))"
    );
    private static final Pattern SYSTEM_PROMPT_REQUEST = Pattern.compile(
            "(?is)\\b(system\\s+prompt|developer\\s+message|prompt\\s+file|tool\\s+strategy|final\\s+answer\\s+module|guardrail|configuration|config|core\\s+identity|language\\s+matching)\\b"
                    + "|系统提示|提示词|开发者消息|工具策略|最终回答模块|护栏|配置|核心身份|语言匹配"
    );
    private static final Pattern SYSTEM_PROMPT_LEAKAGE = Pattern.compile(
            "(?is)(system\\s+configuration\\s+summary|core\\s+identity|language\\s+matching|tool\\s+strategy|final\\s+answer\\s+module|system\\s+prompts?\\s+and\\s+configuration)"
    );
    private static final Pattern ENGLISH_REQUEST = Pattern.compile(
            "(?is)\\b(in|use|answer\\s+in|respond\\s+in|write\\s+in)\\s+English\\b|英文|英语"
    );
    private static final Pattern CHINESE_REQUEST = Pattern.compile(
            "(?is)\\b(in|use|answer\\s+in|respond\\s+in|write\\s+in)\\s+Chinese\\b|中文|汉语|普通话"
    );

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

    private enum ExpectedLanguage {
        ENGLISH,
        CHINESE,
        UNKNOWN
    }

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
            var retrievalMetadata = (toolCalls == null || toolCalls.isEmpty())
                    ? currentTurnCitationHolder.takeRetrievalMetadata(chatSessionId, turnId)
                    : null;
            CitationSelection citationSelection = selectReferencedCitations(assistantMessage.getText(), citations);
            ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(citationSelection.content())
                    .sessionId(chatSessionId)
                    .turnId(turnId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(toolCalls)
                            .citations(citationSelection.citations().isEmpty() ? null : citationSelection.citations())
                            .retrieval(retrievalMetadata)
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
    public void publishPersistedToolResponse(
            com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort.PersistedToolResponse persisted) {
        var committed = persisted.committed();
        if (committed.internal()) {
            return;
        }
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .id(persisted.messageId())
                .turnSeq(persisted.turnSeq())
                .role(ChatMessageDTO.RoleType.TOOL)
                .content(committed.response().responseData())
                .sessionId(committed.sessionId())
                .turnId(committed.turnId())
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(committed.response())
                        .internal(committed.internal() ? Boolean.TRUE : null)
                        .deepThinkPhase(committed.deepThinkPhase())
                        .planStepId(committed.planStepId())
                        .build())
                .build();
        ChatMessageVO vo = chatMessageConverter.toVO(dto);
        sseService.publish(committed.sessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder().message(vo).build())
                .metadata(SseMessage.Metadata.builder().chatMessageId(persisted.messageId()).build())
                .build());
    }

    @Override
    public String streamFinalResponse(String chatSessionId, String turnId, Prompt prompt, LLMService llmService, boolean deepThinking) {
        return streamFinalResponse(chatSessionId, turnId, prompt, llmService, deepThinking, 0);
    }

    private String streamFinalResponse(String chatSessionId,
                                       String turnId,
                                       Prompt prompt,
                                       LLMService llmService,
                                       boolean deepThinking,
                                       int repairAttempt) {
        boolean allowFinalRepair = repairAttempt < MAX_FINAL_REPAIR_ATTEMPTS && hasCurrentUserRequest(prompt);
        // 最终回复阶段：先创建一条空 assistant 消息，拿到 messageId。
        // 后续每个 content chunk 都基于这个 messageId 推送快照，前端才能增量更新同一条消息。
        ChatMessageDTO chatMessageDTO = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .sessionId(chatSessionId)
                .turnId(turnId)
                .metadata(ChatMessageDTO.MetaData.builder().build())
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
        AtomicBoolean invalidFinalResponse = new AtomicBoolean(false);
        AtomicReference<String> repairReason = new AtomicReference<>();

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
                String finalContent;
                synchronized (contentLock) {
                    finalContent = fullContent.toString();
                    if (!StringUtils.hasText(finalContent) && !allowFinalRepair) {
                        finalContent = emptyFinalAnswerFallback(prompt);
                    }
                }
                List<CitationMetadata> pendingCitations = currentTurnCitationHolder.peek(chatSessionId, turnId);
                String invalidReason = finalRepairReason(prompt, finalContent, allowFinalRepair, pendingCitations);
                if (StringUtils.hasText(invalidReason)) {
                    invalidFinalResponse.set(true);
                    repairReason.set(invalidReason);
                    String reasonCode = repairReasonCode(invalidReason);
                    recordCounter("chatagent.final.repair", "reason", reasonCode);
                    log.warn("Final answer failed generic safety guard; rolling back for repair: sessionId={}, turnId={}, chatMessageId={}, reasonCode={}",
                            chatSessionId, turnId, chatMessageDTO.getId(), reasonCode);
                    chatMessageFacadeService.deleteChatMessage(chatMessageDTO.getId());
                    publishTurnRollback(chatSessionId, turnId);
                    streamLatch.countDown();
                    return;
                }

                CitationSelection citationSelection = selectReferencedCitations(
                        finalContent,
                        currentTurnCitationHolder.take(chatSessionId, turnId));
                finalContent = citationSelection.content();
                synchronized (contentLock) {
                    fullContent.setLength(0);
                    fullContent.append(finalContent);
                }
                ChatMessageDTO.MetaData finalMetadata = ChatMessageDTO.MetaData.builder()
                        .citations(citationSelection.citations().isEmpty() ? null : citationSelection.citations())
                        .retrieval(currentTurnCitationHolder.takeRetrievalMetadata(chatSessionId, turnId))
                        .build();
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                updateReq.setContent(finalContent);
                updateReq.setMetadata(finalMetadata);
                chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);
                publishContent(chatSessionId, chatMessageDTO.getId(), snapshotMessage(baseVo, finalContent, finalMetadata));

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
                currentTurnCitationHolder.take(chatSessionId, turnId);
                currentTurnCitationHolder.takeRetrievalMetadata(chatSessionId, turnId);

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

        if (invalidFinalResponse.get()) {
            return streamFinalResponse(
                    chatSessionId,
                    turnId,
                    buildFinalRepairPrompt(prompt, repairReason.get()),
                    llmService,
                    deepThinking,
                    repairAttempt + 1);
        }

        synchronized (contentLock) {
            return fullContent.toString();
        }
    }

    private boolean hasCurrentUserRequest(Prompt prompt) {
        return StringUtils.hasText(latestUserText(prompt));
    }

    private boolean isNoUserContradiction(String content) {
        return StringUtils.hasText(content) && NO_USER_CONTRADICTION.matcher(content).find();
    }

    private String finalRepairReason(Prompt prompt,
                                     String finalContent,
                                     boolean allowFinalRepair,
                                     List<CitationMetadata> pendingCitations) {
        if (!allowFinalRepair) {
            return "";
        }
        if (!StringUtils.hasText(finalContent)) {
            return "it produced no user-visible answer";
        }
        if (isNoUserContradiction(finalContent)) {
            return "it claimed no user question exists";
        }
        if (containsUserVisibleToolCallMarkup(finalContent)) {
            return "it exposed tool-call markup instead of a final user answer";
        }
        int pendingCitationCount = pendingCitations == null ? 0 : pendingCitations.size();
        if (pendingCitationCount > 0
                && !hasSupportedCitationReference(finalContent, pendingCitationCount)
                && usesPendingCitationEvidenceWithoutReference(finalContent, pendingCitations)) {
            return "it answered from retrieved evidence without citation markers";
        }
        if (isLikelyStalePreviousAnswer(prompt, finalContent)) {
            return "it repeated an earlier assistant answer instead of the current request";
        }
        if (isSystemPromptLeakage(prompt, finalContent)) {
            return "it summarized system prompts or configuration instead of the current request";
        }
        String languageFailure = languageMismatchReason(prompt, finalContent);
        if (StringUtils.hasText(languageFailure)) {
            return languageFailure;
        }
        return "";
    }

    private boolean hasSupportedCitationReference(String content, int citationCount) {
        if (!StringUtils.hasText(content) || citationCount <= 0) {
            return false;
        }
        Matcher matcher = CITATION_REFERENCE.matcher(content);
        while (matcher.find()) {
            int citationNumber = parseCitationNumber(matcher.group(1));
            if (citationNumber >= 1 && citationNumber <= citationCount) {
                return true;
            }
        }
        return false;
    }

    private boolean usesPendingCitationEvidenceWithoutReference(String content,
                                                                List<CitationMetadata> pendingCitations) {
        if (!StringUtils.hasText(content) || pendingCitations == null || pendingCitations.isEmpty()) {
            return false;
        }
        String normalizedContent = safeLower(content);
        for (CitationMetadata citation : pendingCitations) {
            for (String token : distinctiveEvidenceTokens(rawCitationSearchText(citation))) {
                if (normalizedContent.contains(token)) {
                    return true;
                }
            }
            if (countSignificantCitationTermMatches(normalizedContent, citation)
                    >= MIN_CITATION_TERM_MATCHES_FOR_UNCITED_USAGE) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUserVisibleToolCallMarkup(String content) {
        return StringUtils.hasText(content) && USER_VISIBLE_TOOL_CALL_MARKUP.matcher(content).find();
    }

    private String emptyFinalAnswerFallback(Prompt prompt) {
        if (expectedLanguage(prompt) == ExpectedLanguage.CHINESE) {
            return "抱歉，模型没有返回可显示的内容。请再试一次。";
        }
        return "Sorry, the model did not return a visible answer. Please try again.";
    }

    private Prompt buildFinalRepairPrompt(Prompt prompt, String reason) {
        String latestUserText = latestUserText(prompt);
        if (!StringUtils.hasText(latestUserText)) {
            return prompt;
        }
        String normalizedReason = StringUtils.hasText(reason) ? reason : "it did not answer the current request";
        String specificRepairInstruction = repairSpecificInstruction(prompt, normalizedReason);
        List<Message> sourceMessages = prompt.getInstructions();
        List<Message> repairMessages = new ArrayList<>(sourceMessages.size() + 1);
        repairMessages.add(new SystemMessage(
                "The previous model output for this same turn was invalid because " + normalizedReason + ". "
                        + "That is false. The current user request is:\n"
                        + latestUserText
                        + specificRepairInstruction
                        + "\nAnswer that request directly. Do not mention missing user input, missing context, "
                        + "the invalid output, or this repair instruction. Use the same language as the current "
                        + "user request unless it explicitly asks for another language. Preserve the turn's "
                        + "existing tool, retrieval, citation, and scope decisions; do not choose a new route, "
                        + "invoke a tool, or invent evidence during repair."));
        repairMessages.addAll(sourceMessages);
        return new Prompt(repairMessages, prompt.getOptions());
    }

    private String repairSpecificInstruction(Prompt prompt, String reason) {
        if (!StringUtils.hasText(reason)) {
            return "";
        }
        if (expectedLanguage(prompt) == ExpectedLanguage.ENGLISH && reason.contains("Chinese text")) {
            return "\nThe final answer must be entirely in English. Do not include Chinese words, "
                    + "Chinese sentences, or translated Chinese fragments.";
        }
        if (reason.contains("retrieved evidence without citation markers")) {
            return "\nThe current turn has retrieved evidence in the conversation. If you use any retrieved fact, "
                    + "cite it inline with the exact [n] marker shown in the tool response. Do not answer retrieved "
                    + "facts without citation markers.";
        }
        if (reason.contains("tool-call markup")) {
            return "\nDo not emit XML, JSON, function-call blocks, or internal tool names. The final answer must be "
                    + "plain user-facing text. If no tool result is available, answer from the current conversation "
                    + "context or state the limitation briefly.";
        }
        return "";
    }

    private String repairReasonCode(String reason) {
        if (reason == null) return "unknown";
        if (reason.contains("no user-visible answer")) return "empty_answer";
        if (reason.contains("no user question")) return "no_user_contradiction";
        if (reason.contains("tool-call markup")) return "tool_markup";
        if (reason.contains("citation markers")) return "citation_missing";
        if (reason.contains("earlier assistant answer")) return "stale_answer";
        if (reason.contains("system prompts")) return "system_leak";
        if (reason.contains("language") || reason.contains("Chinese text")) return "language_mismatch";
        return "unknown";
    }

    private boolean isSystemPromptLeakage(Prompt prompt, String finalContent) {
        if (!StringUtils.hasText(finalContent)) {
            return false;
        }
        String latestUserText = latestUserText(prompt);
        if (!StringUtils.hasText(latestUserText) || SYSTEM_PROMPT_REQUEST.matcher(latestUserText).find()) {
            return false;
        }
        return SYSTEM_PROMPT_LEAKAGE.matcher(finalContent).find();
    }

    private String languageMismatchReason(Prompt prompt, String finalContent) {
        if (!StringUtils.hasText(finalContent)) {
            return "";
        }
        ExpectedLanguage expectedLanguage = expectedLanguage(prompt);
        if (expectedLanguage == ExpectedLanguage.CHINESE && !isChineseDominant(finalContent)) {
            return "it did not answer in the current user's requested Chinese language";
        }
        if (expectedLanguage == ExpectedLanguage.ENGLISH && hasSubstantialChineseText(finalContent)) {
            return "it included Chinese text even though the current user request calls for English";
        }
        return "";
    }

    private ExpectedLanguage expectedLanguage(Prompt prompt) {
        String latestUserText = latestUserText(prompt);
        if (!StringUtils.hasText(latestUserText)) {
            return ExpectedLanguage.UNKNOWN;
        }
        if (ENGLISH_REQUEST.matcher(latestUserText).find()) {
            return ExpectedLanguage.ENGLISH;
        }
        if (CHINESE_REQUEST.matcher(latestUserText).find()) {
            return ExpectedLanguage.CHINESE;
        }
        int cjkCount = countMatches(CJK, latestUserText);
        int latinWordCount = countMatches(LATIN_WORD, latestUserText);
        if (cjkCount >= 4 && cjkCount >= latinWordCount) {
            return ExpectedLanguage.CHINESE;
        }
        if (latinWordCount >= 3 && cjkCount < 4) {
            return ExpectedLanguage.ENGLISH;
        }
        return ExpectedLanguage.UNKNOWN;
    }

    private boolean isChineseDominant(String text) {
        int cjkCount = countMatches(CJK, text);
        int latinWordCount = countMatches(LATIN_WORD, text);
        return cjkCount >= 4 && cjkCount >= latinWordCount;
    }

    private boolean hasSubstantialChineseText(String text) {
        return countMatches(CJK, text) > MAX_ENGLISH_ANSWER_CJK_CHARS;
    }

    private int countMatches(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int count = 0;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean isLikelyStalePreviousAnswer(Prompt prompt, String finalContent) {
        if (!StringUtils.hasText(finalContent)) {
            return false;
        }
        String latestUserText = latestUserText(prompt);
        if (!StringUtils.hasText(latestUserText) || REPEAT_REQUEST.matcher(latestUserText).find()) {
            return false;
        }
        String previousAssistantText = previousAssistantText(prompt);
        if (!StringUtils.hasText(previousAssistantText)) {
            return false;
        }
        if (!isHighlySimilar(finalContent, previousAssistantText)) {
            return false;
        }
        Set<String> requestTerms = significantTerms(latestUserText);
        if (requestTerms.isEmpty()) {
            return false;
        }
        Set<String> answerTerms = significantTerms(finalContent);
        long overlap = requestTerms.stream().filter(answerTerms::contains).count();
        return overlap == 0 || (requestTerms.size() >= 4 && overlap <= 1);
    }

    private String previousAssistantText(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return "";
        }
        List<Message> messages = prompt.getInstructions();
        int latestUserIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMessage
                    && StringUtils.hasText(userMessage.getText())) {
                latestUserIndex = i;
                break;
            }
        }
        if (latestUserIndex <= 0) {
            return "";
        }
        for (int i = latestUserIndex - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistantMessage
                    && StringUtils.hasText(assistantMessage.getText())) {
                return assistantMessage.getText();
            }
        }
        return "";
    }

    private boolean isHighlySimilar(String left, String right) {
        String normalizedLeft = normalizeForComparison(left);
        String normalizedRight = normalizeForComparison(right);
        if (!StringUtils.hasText(normalizedLeft) || !StringUtils.hasText(normalizedRight)) {
            return false;
        }
        int prefixLength = Math.min(80, normalizedRight.length());
        if (prefixLength >= 40 && normalizedLeft.contains(normalizedRight.substring(0, prefixLength))) {
            return true;
        }
        Set<String> leftTerms = significantTerms(normalizedLeft);
        Set<String> rightTerms = significantTerms(normalizedRight);
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) {
            return false;
        }
        Set<String> intersection = new HashSet<>(leftTerms);
        intersection.retainAll(rightTerms);
        double similarity = (double) intersection.size() / Math.min(leftTerms.size(), rightTerms.size());
        return similarity >= 0.75d;
    }

    private Set<String> significantTerms(String text) {
        Set<String> terms = new HashSet<>();
        if (!StringUtils.hasText(text)) {
            return terms;
        }
        java.util.regex.Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() >= 4 && !GENERIC_WORDS.contains(word)) {
                terms.add(word);
            }
        }
        return terms;
    }

    private String normalizeForComparison(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String latestUserText(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return "";
        }
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMessage
                    && StringUtils.hasText(userMessage.getText())) {
                return userMessage.getText();
            }
        }
        for (Message message : messages) {
            String anchoredRequest = currentUserRequestAnchor(message.getText());
            if (StringUtils.hasText(anchoredRequest)) {
                return anchoredRequest;
            }
        }
        return "";
    }

    private String currentUserRequestAnchor(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n");
        int markerIndex = normalized.indexOf(CURRENT_USER_REQUEST_MARKER);
        if (markerIndex < 0) {
            return "";
        }
        int valueStart = markerIndex + CURRENT_USER_REQUEST_MARKER.length();
        String remainder = normalized.substring(valueStart);
        int valueEnd = earliestDelimiterIndex(remainder);
        String candidate = (valueEnd >= 0 ? remainder.substring(0, valueEnd) : remainder).trim();
        if (!StringUtils.hasText(candidate) || candidate.contains(NO_USER_ROLE_MESSAGE)) {
            return "";
        }
        return candidate;
    }

    private int earliestDelimiterIndex(String value) {
        int result = -1;
        for (String delimiter : List.of(
                "\n- Attached session files:",
                "\n- Relevant long-term memory:",
                "\nThe current user request is authoritative.",
                "\n# Rules")) {
            int candidate = value.indexOf(delimiter);
            if (candidate >= 0 && (result < 0 || candidate < result)) {
                result = candidate;
            }
        }
        return result;
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
                        .retrieval(currentTurnCitationHolder.peekRetrievalMetadata(chatSessionId, turnId))
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
        }

        CitationSelection citationSelection = selectReferencedCitations(
                finalContent,
                currentTurnCitationHolder.take(chatSessionId, turnId));
        finalContent = citationSelection.content();
        ChatMessageDTO.MetaData finalMetadata = ChatMessageDTO.MetaData.builder()
                .citations(citationSelection.citations().isEmpty() ? null : citationSelection.citations())
                .retrieval(currentTurnCitationHolder.takeRetrievalMetadata(chatSessionId, turnId))
                .build();
        UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
        updateReq.setContent(finalContent);
        updateReq.setMetadata(finalMetadata);
        chatMessageFacadeService.updateChatMessage(chatMessageDTO.getId(), updateReq);
        finalSnapshot = snapshotMessage(baseVo, finalContent, finalMetadata);
        // 再推一次最终快照，确保前端与数据库里的最终内容一致。
        publishContent(chatSessionId, chatMessageDTO.getId(), finalSnapshot);
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

        // Tool-call assistant messages are persisted only after shared structural preflight
        // by AgentToolExecutionEngine. Raw model arguments must never reach storage here.

        return bufferedResponse;
    }

    private boolean shouldPersistInternalDecision(boolean deepThinking, String deepThinkPhase, String planStepId) {
        return deepThinking
                || (deepThinkPhase != null && !deepThinkPhase.isBlank())
                || (planStepId != null && !planStepId.isBlank());
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
    @Override
    public void persistInternalAssistantToolCalls(String chatSessionId, String turnId,
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
        return snapshotMessage(baseVo, content, baseVo.getMetadata());
    }

    private ChatMessageVO snapshotMessage(ChatMessageVO baseVo, String content, ChatMessageDTO.MetaData metadata) {
        // 基于最初创建的 VO 克隆一份快照，只替换 content。
        // 这样 id/session/turn/metadata/seqNo 保持稳定，前端能持续更新同一条消息。
        return ChatMessageVO.builder()
                .id(baseVo.getId())
                .sessionId(baseVo.getSessionId())
                .turnId(baseVo.getTurnId())
                .turnSeq(baseVo.getTurnSeq())
                .role(baseVo.getRole())
                .content(content)
                .metadata(metadata)
                .seqNo(baseVo.getSeqNo())
                .build();
    }

    private CitationSelection selectReferencedCitations(String content, List<CitationMetadata> citations) {
        if (!StringUtils.hasText(content)) {
            return new CitationSelection(content, List.of());
        }

        List<CitationMetadata> availableCitations = citations == null ? List.of() : citations;
        Matcher matcher = CITATION_REFERENCE.matcher(content);
        Map<Integer, Integer> citationRemap = new LinkedHashMap<>();
        while (matcher.find()) {
            if (isNonSupportingCitationContext(content, matcher.start(), matcher.end())) {
                continue;
            }
            int originalNumber = parseCitationNumber(matcher.group(1));
            if (originalNumber < 1
                    || originalNumber > availableCitations.size()) {
                continue;
            }
            int resolvedNumber = resolveCitationNumberForReference(
                    content,
                    matcher.start(),
                    matcher.end(),
                    originalNumber,
                    availableCitations);
            if (!citationRemap.containsKey(resolvedNumber)) {
                citationRemap.put(resolvedNumber, citationRemap.size() + 1);
            }
        }

        if (!matcher.reset().find()) {
            return new CitationSelection(content, List.of());
        }

        List<CitationMetadata> referencedCitations = citationRemap.keySet().stream()
                .map(index -> availableCitations.get(index - 1))
                .toList();
        matcher.reset();
        StringBuffer renumberedContent = new StringBuffer();
        boolean removedUnsupportedReference = false;
        while (matcher.find()) {
            if (isNonSupportingCitationContext(content, matcher.start(), matcher.end())) {
                matcher.appendReplacement(renumberedContent, "");
                removedUnsupportedReference = true;
                continue;
            }
            int originalNumber = parseCitationNumber(matcher.group(1));
            int resolvedNumber = originalNumber >= 1 && originalNumber <= availableCitations.size()
                    ? resolveCitationNumberForReference(
                    content,
                    matcher.start(),
                    matcher.end(),
                    originalNumber,
                    availableCitations)
                    : originalNumber;
            Integer newNumber = citationRemap.get(resolvedNumber);
            if (newNumber == null) {
                matcher.appendReplacement(renumberedContent, "");
                removedUnsupportedReference = true;
                continue;
            }
            matcher.appendReplacement(renumberedContent, Matcher.quoteReplacement("[" + newNumber + "]"));
        }
        matcher.appendTail(renumberedContent);
        String normalizedContent = removedUnsupportedReference
                ? normalizeCitationWhitespace(renumberedContent.toString())
                : renumberedContent.toString();
        return new CitationSelection(normalizedContent, referencedCitations);
    }

    private boolean isNonSupportingCitationContext(String content, int citationStart, int citationEnd) {
        return NON_SUPPORTING_CITATION_CONTEXT.matcher(citationContext(content, citationStart, citationEnd)).find();
    }

    private int resolveCitationNumberForReference(String content,
                                                  int citationStart,
                                                  int citationEnd,
                                                  int originalNumber,
                                                  List<CitationMetadata> availableCitations) {
        if (originalNumber < 1 || originalNumber > availableCitations.size()) {
            return originalNumber;
        }
        List<String> distinctiveTokens = distinctiveEvidenceTokens(
                citationContext(content, citationStart, citationEnd));
        int sourceCueNumber = resolveCitationNumberBySourceCue(
                content,
                citationStart,
                citationEnd,
                originalNumber,
                availableCitations,
                distinctiveTokens);
        if (sourceCueNumber != originalNumber) {
            return sourceCueNumber;
        }
        if (distinctiveTokens.isEmpty()) {
            return originalNumber;
        }

        int originalMatches = countCitationTokenMatches(
                availableCitations.get(originalNumber - 1),
                distinctiveTokens);
        if (originalMatches == distinctiveTokens.size()) {
            return originalNumber;
        }

        int bestNumber = originalNumber;
        int bestMatches = originalMatches;
        for (int i = 0; i < availableCitations.size(); i++) {
            int candidateNumber = i + 1;
            if (candidateNumber == originalNumber) {
                continue;
            }
            int matches = countCitationTokenMatches(availableCitations.get(i), distinctiveTokens);
            if (matches > bestMatches) {
                bestMatches = matches;
                bestNumber = candidateNumber;
            }
        }
        if (bestNumber != originalNumber && bestMatches > originalMatches) {
            log.info("Remapped citation reference by distinctive evidence token: original={}, remapped={}, tokenMatches={}/{}",
                    originalNumber, bestNumber, bestMatches, distinctiveTokens.size());
        }
        return bestNumber;
    }

    private int resolveCitationNumberBySourceCue(String content,
                                                 int citationStart,
                                                 int citationEnd,
                                                 int originalNumber,
                                                 List<CitationMetadata> availableCitations,
                                                 List<String> distinctiveTokens) {
        RagSourceType preferredSourceType = preferredCitationSourceType(
                citationContext(content, citationStart, citationEnd));
        if (preferredSourceType == null
                || originalNumber < 1
                || originalNumber > availableCitations.size()
                || availableCitations.get(originalNumber - 1).sourceType() == preferredSourceType) {
            return originalNumber;
        }

        int bestNumber = originalNumber;
        int bestMatches = -1;
        boolean tie = false;
        int preferredCount = 0;
        for (int i = 0; i < availableCitations.size(); i++) {
            CitationMetadata candidate = availableCitations.get(i);
            if (candidate.sourceType() != preferredSourceType) {
                continue;
            }
            preferredCount++;
            int candidateNumber = i + 1;
            int matches = countCitationTokenMatches(candidate, distinctiveTokens);
            if (matches > bestMatches) {
                bestMatches = matches;
                bestNumber = candidateNumber;
                tie = false;
            } else if (matches == bestMatches) {
                tie = true;
            }
        }
        if (preferredCount == 1 || (bestMatches > 0 && !tie)) {
            log.info("Remapped citation reference by source cue: original={}, remapped={}, sourceType={}, tokenMatches={}",
                    originalNumber, bestNumber, preferredSourceType, Math.max(bestMatches, 0));
            return bestNumber;
        }
        return originalNumber;
    }

    private RagSourceType preferredCitationSourceType(String context) {
        if (!StringUtils.hasText(context)) {
            return null;
        }
        if (SESSION_FILE_CITATION_CONTEXT.matcher(context).find()) {
            return RagSourceType.SESSION_FILE;
        }
        return null;
    }

    private String citationContext(String content, int citationStart, int citationEnd) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        int start = Math.max(0, citationStart - 220);
        int end = Math.min(content.length(), citationEnd + 80);
        return content.substring(start, end);
    }

    private List<String> distinctiveEvidenceTokens(String context) {
        if (!StringUtils.hasText(context)) {
            return List.of();
        }
        Matcher matcher = DISTINCTIVE_EVIDENCE_TOKEN.matcher(context);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            tokens.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(tokens);
    }

    private int countCitationTokenMatches(CitationMetadata citation, List<String> tokens) {
        if (citation == null || tokens == null || tokens.isEmpty()) {
            return 0;
        }
        String searchable = citationSearchText(citation);
        int count = 0;
        for (String token : tokens) {
            if (searchable.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private int countSignificantCitationTermMatches(String normalizedContent, CitationMetadata citation) {
        if (!StringUtils.hasText(normalizedContent) || citation == null) {
            return 0;
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(citationSearchText(citation));
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() < MIN_CITATION_TERM_LENGTH
                    || GENERIC_WORDS.contains(term)
                    || CITATION_EVIDENCE_STOP_WORDS.contains(term)) {
                continue;
            }
            terms.add(term);
        }
        int matches = 0;
        for (String term : terms) {
            if (normalizedContent.contains(term)) {
                matches++;
            }
        }
        return matches;
    }

    private String citationSearchText(CitationMetadata citation) {
        return safeLower(rawCitationSearchText(citation));
    }

    private String rawCitationSearchText(CitationMetadata citation) {
        return String.join(" ",
                citation.documentName() == null ? "" : citation.documentName(),
                citation.sectionPath() == null ? "" : citation.sectionPath(),
                citation.snippet() == null ? "" : citation.snippet());
    }

    private String safeLower(String value) {
        return StringUtils.hasText(value) ? value.toLowerCase(Locale.ROOT) : "";
    }

    private String normalizeCitationWhitespace(String content) {
        return content
                .replaceAll("[ \\t]+([,.;:!?])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("(?m)[ \\t]+$", "");
    }

    private int parseCitationNumber(String rawNumber) {
        try {
            return Integer.parseInt(rawNumber);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record CitationSelection(String content, List<CitationMetadata> citations) {
    }

    private void recordCounter(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (Exception e) {
            log.warn("Failed to record final-repair counter: name={}, error={}", name, e.getMessage());
        }
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
                msg.setMetadata(meta);
                UpdateChatMessageRequest updateReq = new UpdateChatMessageRequest();
                updateReq.setMetadata(meta);
                chatMessageFacadeService.updateChatMessage(msg.getId(), updateReq);
                publishContent(chatSessionId, msg.getId(), chatMessageConverter.toVO(msg));
                log.info("Attached trace metadata to message {} for turn {}", msg.getId(), turnId);
                return;
            }
        }
        log.warn("No non-internal assistant message found for turn {} to attach trace", turnId);
    }
}
