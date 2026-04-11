package com.yulong.chatagent.conversation.application;

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
 * Coordinates one user turn from the conversation entrypoint.
 * <p>
 * This service is intentionally placed above message CRUD so that turn-level
 * concerns such as validation, session checks, event dispatch, and later
 * planning/retrieval steps can evolve without polluting the message facade.
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

    public ConversationOrchestratorService(ChatSessionFacadeService chatSessionFacadeService,
                                           ChatSessionRepository chatSessionRepository,
                                           ChatMessageFacadeService chatMessageFacadeService,
                                           ConversationTurnPreparationService conversationTurnPreparationService,
                                           ChatEventDispatcher chatEventDispatcher,
                                           ConversationTurnCompletionPublisher conversationTurnCompletionPublisher,
                                           SseService sseService) {
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.conversationTurnPreparationService = conversationTurnPreparationService;
        this.chatEventDispatcher = chatEventDispatcher;
        this.conversationTurnCompletionPublisher = conversationTurnCompletionPublisher;
        this.sseService = sseService;
    }

    /**
     * Handles one user input turn and starts the downstream conversation flow.
     *
     * @param request user message creation request
     * @return response describing the created user-side message record
     */
    @Transactional
    public CreateChatMessageResponse handleUserTurn(CreateChatMessageRequest request) {
        validateRequest(request);
        ConversationTurnContext turnContext = buildTurnContext(request);
        verifyTurnContext(turnContext);
        prepareAndDispatchTurn(turnContext);
        return turnContext.createdUserMessage();
    }

    private void validateRequest(CreateChatMessageRequest request) {
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
        CreateChatMessageRequest normalizedRequest = normalizeRequest(request);

        ChatSessionVO chatSession = chatSessionFacadeService.getChatSession(normalizedRequest.getSessionId());
        String resolvedAgentId = requireAgentId(chatSession, normalizedRequest.getSessionId());

        CreateChatMessageResponse createdMessage = chatMessageFacadeService.createChatMessage(normalizedRequest);
        List<ChatMessageDTO> recentHistory = loadRecentHistory(normalizedRequest.getSessionId());

        log.info("Conversation turn context built: sessionId={}, agentId={}, userMessageId={}, recentHistorySize={}",
                normalizedRequest.getSessionId(),
                resolvedAgentId,
                createdMessage.getChatMessageId(),
                recentHistory.size());

        return new ConversationTurnContext(
                normalizedRequest,
                chatSession,
                createdMessage,
                recentHistory
        );
    }

    private void verifyTurnContext(ConversationTurnContext turnContext) {
        boolean containsCreatedMessage = turnContext.recentHistory().stream()
                .anyMatch(message -> turnContext.createdUserMessage().getChatMessageId().equals(message.getId()));
        if (!containsCreatedMessage) {
            throw new BizException("Failed to reload recent history after persisting user message");
        }
    }

    private void prepareAndDispatchTurn(ConversationTurnContext turnContext) {
        String resolvedAgentId = requireAgentId(turnContext.session(), turnContext.request().getSessionId());
        TurnPreparationResult preparationResult = conversationTurnPreparationService.prepare(
                resolvedAgentId,
                turnContext.request().getSessionId(),
                turnContext.request().getContent()
        );
        if (preparationResult.isDirectReply()) {
            persistAndPushDirectReply(
                    turnContext.request().getSessionId(),
                    turnContext.request().getTurnId(),
                    preparationResult.directReply()
            );
            conversationTurnCompletionPublisher.publishCompletedTurn(
                    turnContext.request().getSessionId(),
                    turnContext.request().getTurnId()
            );
            return;
        }

        chatEventDispatcher.dispatch(
                new ChatEvent(
                        resolvedAgentId,
                        turnContext.request().getSessionId(),
                        turnContext.request().getTurnId(),
                        turnContext.createdUserMessage().getChatMessageId(),
                        turnContext.request().getContent(),
                        turnContext.historySize(),
                        preparationResult.intentResolution(),
                        preparationResult.rewrittenInput(),
                        resolveSessionUserId(turnContext.request().getSessionId())
                )
        );
    }

    private void persistAndPushDirectReply(String sessionId, String turnId, String content) {
        CreateChatMessageResponse savedMessage = chatMessageFacadeService.createChatMessage(
                CreateChatMessageRequest.builder()
                        .sessionId(sessionId)
                        .turnId(turnId)
                        .role(ChatMessageDTO.RoleType.ASSISTANT)
                        .content(content)
                        .build()
        );

        ChatMessageVO messageVo = ChatMessageVO.builder()
                .id(savedMessage.getChatMessageId())
                .sessionId(sessionId)
                .turnId(turnId)
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
        return chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, RECENT_HISTORY_LIMIT);
    }

    private CreateChatMessageRequest normalizeRequest(CreateChatMessageRequest request) {
        String turnId = request.getTurnId();
        if (turnId == null || turnId.isBlank()) {
            turnId = UUID.randomUUID().toString();
        } else {
            turnId = turnId.trim();
        }
        return CreateChatMessageRequest.builder()
                .sessionId(request.getSessionId().trim())
                .turnId(turnId)
                .role(request.getRole())
                .content(request.getContent().trim())
                .metadata(request.getMetadata())
                .build();
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
        ChatSessionDTO chatSession = chatSessionRepository.findById(sessionId);
        return chatSession == null ? null : chatSession.getUserId();
    }
}
