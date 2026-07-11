package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.agent.runtime.AgentExecutionModeResolver;
import com.yulong.chatagent.agent.runtime.AgentSessionFileSummaryResolver;
import com.yulong.chatagent.conversation.application.model.ConversationTurnContext;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.event.ChatEventDispatcher;
import com.yulong.chatagent.conversation.model.SseMessage;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
import com.yulong.chatagent.conversation.model.vo.ChatSessionVO;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.port.ChatSessionRepository;
import com.yulong.chatagent.conversation.summary.ConversationTurnCompletionPublisher;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.intent.application.ConversationTurnPreparationService;
import com.yulong.chatagent.intent.application.TurnPreparationResult;
import com.yulong.chatagent.intent.application.TurnPreparationContext;
import com.yulong.chatagent.intent.application.TurnPreparationContextAssembler;
import com.yulong.chatagent.sse.SseService;
import com.yulong.chatagent.support.dto.ChatSessionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 单轮用户输入的会话编排器。
 * <p>
 * 这是 conversation 模块最核心的主服务，位于：
 * <ul>
 *     <li>上游同步 HTTP 入口；</li>
 *     <li>下游异步 Agent runtime；</li>
 *     <li>中间的意图准备、direct reply 分流和事件派发。</li>
 * </ul>
 * 它做的不是“生成回答”，而是把一次用户输入稳定地组织成一个 turn：
 * 校验请求、保存用户消息、验证上下文一致性、执行 prepare，然后决定是直接回复还是派发 ChatEvent。
 */
@Service
@Slf4j
public class ConversationOrchestratorService {
    private static final int RECENT_HISTORY_LIMIT = 12;
    private static final Pattern TURN_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    private final ChatSessionFacadeService chatSessionFacadeService;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ConversationTurnPreparationService conversationTurnPreparationService;
    private final ChatEventDispatcher chatEventDispatcher;
    private final ConversationTurnCompletionPublisher conversationTurnCompletionPublisher;
    private final SseService sseService;
    private final AgentExecutionModeResolver executionModeResolver;
    private final AgentSessionFileSummaryResolver sessionAssetSummaryResolver;
    private final TurnPreparationContextAssembler turnPreparationContextAssembler;

    public ConversationOrchestratorService(ChatSessionFacadeService chatSessionFacadeService,
                                           ChatSessionRepository chatSessionRepository,
                                           ChatMessageFacadeService chatMessageFacadeService,
                                           ConversationTurnPreparationService conversationTurnPreparationService,
                                           ChatEventDispatcher chatEventDispatcher,
                                           ConversationTurnCompletionPublisher conversationTurnCompletionPublisher,
                                           SseService sseService,
                                           AgentExecutionModeResolver executionModeResolver,
                                           AgentSessionFileSummaryResolver sessionAssetSummaryResolver,
                                           TurnPreparationContextAssembler turnPreparationContextAssembler) {
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.conversationTurnPreparationService = conversationTurnPreparationService;
        this.chatEventDispatcher = chatEventDispatcher;
        this.conversationTurnCompletionPublisher = conversationTurnCompletionPublisher;
        this.sseService = sseService;
        this.executionModeResolver = executionModeResolver;
        this.sessionAssetSummaryResolver = sessionAssetSummaryResolver;
        this.turnPreparationContextAssembler = turnPreparationContextAssembler;
    }

    /**
     * 处理一次用户输入，并启动后续对话流程。
     *
     * @param request 用户消息创建请求
     * @return 已创建的用户侧消息记录
     */
    @Transactional
    public CreateChatMessageResponse handleUserTurn(CreateChatMessageRequest request) {
        // 这里的事务只覆盖“入口编排”这段同步链路：
        // 用户消息落库、最近历史读取、prepare 判断、构造 direct reply 或 ChatEvent。
        // 真正耗时的 Agent 运行通常发生在事务外的异步线程里。
        validateRequest(request);
        ConversationTurnContext turnContext = buildTurnContext(request);
        verifyTurnContext(turnContext);
        prepareAndDispatchTurn(turnContext);
        return turnContext.createdUserMessage();
    }

    private void validateRequest(CreateChatMessageRequest request) {
        // 对话入口只接受 USER 消息；assistant/tool/system 消息只能由后端运行时创建。
        Assert.notNull(request, "CreateChatMessageRequest must not be null");
        Assert.hasText(request.getSessionId(), "SessionId must not be empty");
        Assert.hasText(request.getContent(), "Content must not be empty");
        Assert.notNull(request.getRole(), "Role must not be null");
        if (request.getRole() != ChatMessageDTO.RoleType.USER) {
            throw new BizException("Conversation entrypoint only accepts user messages");
        }
        if (StringUtils.hasText(request.getTurnId())
                && !TURN_ID_PATTERN.matcher(request.getTurnId().trim()).matches()) {
            throw new BizException("turnId must be a canonical lowercase UUID");
        }
    }

    private ConversationTurnContext buildTurnContext(CreateChatMessageRequest request) {
        // turnId 是整轮对话的稳定关联键，后续 assistant/tool 消息、SSE、metrics 都靠它串起来。
        CreateChatMessageRequest normalizedRequest = normalizeRequest(request);

        ChatSessionVO chatSession = chatSessionFacadeService.getChatSession(normalizedRequest.getSessionId());
        String resolvedAgentId = requireAgentId(chatSession, normalizedRequest.getSessionId());
        AgentExecutionMode resolvedExecutionMode = executionModeResolver.resolve(normalizedRequest.getExecutionMode());
        Long turnSeq = chatSessionRepository.allocateNextTurnSeq(normalizedRequest.getSessionId());
        if (turnSeq == null || turnSeq <= 0L) {
            throw new BizException("Failed to allocate turn sequence for session: " + normalizedRequest.getSessionId());
        }
        CreateChatMessageRequest requestWithMode = withResolvedExecutionMode(normalizedRequest, resolvedExecutionMode);
        CreateChatMessageRequest sequencedRequest = withTurnSeq(requestWithMode, turnSeq);

        // 先保存用户消息，再读取最近历史，确保当前输入也进入 Agent 的 L1 记忆候选。
        CreateChatMessageResponse createdMessage = chatMessageFacadeService.createChatMessage(sequencedRequest);
        List<ChatMessageDTO> recentHistory = loadRecentHistory(sequencedRequest.getSessionId());
        String sessionAssetSummary = sessionAssetSummaryResolver.resolveForSession(sequencedRequest.getSessionId());

        log.info("Conversation turn context built: sessionId={}, agentId={}, userMessageId={}, turnSeq={}, recentHistorySize={}",
                sequencedRequest.getSessionId(),
                resolvedAgentId,
                createdMessage.getChatMessageId(),
                turnSeq,
                recentHistory.size());

        return new ConversationTurnContext(
                sequencedRequest,
                chatSession,
                createdMessage,
                recentHistory,
                sessionAssetSummary,
                resolvedExecutionMode
        );
    }

    private void verifyTurnContext(ConversationTurnContext turnContext) {
        // 如果保存后读回的最近历史不包含当前用户消息，Agent 会拿不到最新输入，必须立即失败。
        boolean containsCreatedMessage = turnContext.recentHistory().stream()
                .anyMatch(message -> turnContext.createdUserMessage().getChatMessageId().equals(message.getId()));
        if (!containsCreatedMessage) {
            throw new BizException("Failed to reload recent history after persisting user message");
        }
    }

    private void prepareAndDispatchTurn(ConversationTurnContext turnContext) {
        String resolvedAgentId = requireAgentId(turnContext.session(), turnContext.request().getSessionId());
        // 意图准备会做分类、查询改写和澄清判断。澄清类结果会直接回复，不进入 Agent runtime。
        TurnPreparationContext preparationContext = turnPreparationContextAssembler.assemble(
                resolvedAgentId,
                turnContext.request().getSessionId(),
                turnContext.request().getContent(),
                turnContext.createdUserMessage().getChatMessageId(),
                turnContext.recentHistory(),
                turnContext.sessionAssetSummary(),
                turnContext.executionMode()
        );
        TurnPreparationResult preparationResult = conversationTurnPreparationService.prepare(preparationContext);
        if (preparationResult.isDirectReply()) {
            // 直接回复通常来自系统澄清或路由层决策，仍然要落库并推 SSE，前端体验保持一致。
            persistAndPushDirectReply(
                    turnContext.request().getSessionId(),
                    turnContext.request().getTurnId(),
                    turnContext.request().getTurnSeq(),
                    preparationResult.directReply()
            );
            conversationTurnCompletionPublisher.publishCompletedTurn(
                    turnContext.request().getSessionId(),
                    turnContext.request().getTurnId()
            );
            return;
        }

        // 非直接回复时，这一层并不直接调用 ChatAgent.run()。
        // 它只把本轮最小运行快照包装成 ChatEvent，交给后续异步执行链处理。
        // 这样同步入口线程可以尽快返回，前端后续主要通过 SSE 接收结果。
        chatEventDispatcher.dispatch(
                new ChatEvent(
                        resolvedAgentId,
                        turnContext.request().getSessionId(),
                        turnContext.request().getTurnId(),
                        turnContext.request().getTurnSeq(),
                        turnContext.createdUserMessage().getChatMessageId(),
                        turnContext.request().getContent(),
                        turnContext.historySize(),
                        preparationResult.intentResolution(),
                        preparationResult.rewrittenInput(),
                        resolveSessionUserId(turnContext.request().getSessionId()),
                        turnContext.executionMode(),
                        preparationResult.executionContract()
                )
        );
    }

    private void persistAndPushDirectReply(String sessionId, String turnId, Long turnSeq, String content) {
        // direct reply 由编排层直接产生，不经过 Agent runtime，也不存在 token 级流式过程。
        // 但为了让前端协议统一，仍然要：
        // 1. 持久化一条 ASSISTANT 消息；
        // 2. 发送 AI_GENERATED_CONTENT；
        // 3. 发送 AI_DONE。
        CreateChatMessageResponse savedMessage = chatMessageFacadeService.createChatMessage(
                CreateChatMessageRequest.builder()
                        .sessionId(sessionId)
                        .turnId(turnId)
                        .turnSeq(turnSeq)
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content(content)
                        .build()
        );

        ChatMessageVO messageVo = ChatMessageVO.builder()
                .id(savedMessage.getChatMessageId())
                .sessionId(sessionId)
                .turnId(turnId)
                .turnSeq(turnSeq)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .build();

        sseService.publish(sessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(messageVo)
                        .build())
                .build());

        sseService.publish(sessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_DONE)
                .payload(SseMessage.Payload.builder()
                        .done(true)
                        .build())
                .build());
    }

    private List<ChatMessageDTO> loadRecentHistory(String sessionId) {
        // recent history 只取最近窗口，供后续上下文检查和 Agent memory 候选使用，
        // 这里不做完整会话历史加载。
        return chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, RECENT_HISTORY_LIMIT);
    }

    private CreateChatMessageRequest normalizeRequest(CreateChatMessageRequest request) {
        // 前端可以传入 turnId；没有传时后端生成，保证一次用户输入有唯一 turn。
        String turnId = request.getTurnId();
        if (turnId == null || turnId.isBlank()) {
            turnId = UUID.randomUUID().toString();
        } else {
            turnId = turnId.trim();
        }
        return CreateChatMessageRequest.builder()
                .sessionId(request.getSessionId().trim())
                .turnId(turnId)
                .turnSeq(null)
                .role(request.getRole())
                .content(request.getContent().trim())
                .executionMode(request.getExecutionMode())
                .metadata(request.getMetadata())
                .build();
    }

    private CreateChatMessageRequest withTurnSeq(CreateChatMessageRequest request, Long turnSeq) {
        return CreateChatMessageRequest.builder()
                .sessionId(request.getSessionId())
                .turnId(request.getTurnId())
                .turnSeq(turnSeq)
                .role(request.getRole())
                .content(request.getContent())
                .executionMode(request.getExecutionMode())
                .metadata(request.getMetadata())
                .build();
    }

    private CreateChatMessageRequest withResolvedExecutionMode(CreateChatMessageRequest request,
                                                               AgentExecutionMode executionMode) {
        return CreateChatMessageRequest.builder()
                .sessionId(request.getSessionId())
                .turnId(request.getTurnId())
                .turnSeq(request.getTurnSeq())
                .role(request.getRole())
                .content(request.getContent())
                .executionMode(executionMode)
                .metadata(withExecutionMode(request.getMetadata(), executionMode))
                .build();
    }

    private ChatMessageDTO.MetaData withExecutionMode(ChatMessageDTO.MetaData metadata,
                                                      AgentExecutionMode executionMode) {
        ChatMessageDTO.MetaData.MetaDataBuilder builder = ChatMessageDTO.MetaData.builder()
                .executionMode(executionMode);
        if (metadata != null) {
            builder.toolResponse(metadata.getToolResponse())
                    .toolCalls(metadata.getToolCalls())
                    .citations(metadata.getCitations());
        }
        return builder.build();
    }

    private String requireAgentId(ChatSessionVO chatSession, String sessionId) {
        if (chatSession == null) {
            throw new BizException("Chat session not found: " + sessionId);
        }
        String agentId = chatSession.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            throw new BizException("Chat session is missing its internal assistant binding: " + sessionId);
        }
        return agentId;
    }

    private String resolveSessionUserId(String sessionId) {
        // userId 对会话编排层来说不是强依赖，但进入异步 Agent 执行后，
        // toolContext、审计、指标等场景都可能需要它，因此这里尽量提前补齐。
        ChatSessionDTO chatSession = chatSessionRepository.findById(sessionId);
        return chatSession == null ? null : chatSession.getUserId();
    }
}
