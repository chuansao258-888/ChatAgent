package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.application.model.ConversationTurnContext;
import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Default turn orchestrator for the current conversation workflow.
 * <p>
 * The first version keeps the orchestration intentionally small: validate the
 * request, ensure the target session exists, persist the user message through
 * the message facade, and trigger asynchronous downstream processing.
 */
@Service
@Slf4j
public class ConversationOrchestratorServiceImpl implements ConversationOrchestratorService {
    private static final int RECENT_HISTORY_LIMIT = 12;

    private final ChatSessionFacadeService chatSessionFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ConversationOrchestratorServiceImpl(ChatSessionFacadeService chatSessionFacadeService, ChatMessageFacadeService chatMessageFacadeService, ApplicationEventPublisher applicationEventPublisher) {
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.applicationEventPublisher = applicationEventPublisher;
    }


    @Override
    public CreateChatMessageResponse handleUserTurn(CreateChatMessageRequest request) {
        validateRequest(request);
        ConversationTurnContext turnContext = buildTurnContext(request);
        verifyTurnContext(turnContext);
        dispatchTurn(turnContext);
        return turnContext.createdUserMessage();
    }

    private void validateRequest(CreateChatMessageRequest request) {
        Assert.notNull(request, "CreateChatMessageRequest must not be null");
        Assert.hasText(request.getAgentId(), "AgentId must not be empty");
        Assert.hasText(request.getSessionId(), "SessionId must not be empty");
        Assert.hasText(request.getContent(), "Content must not be empty");
        Assert.notNull(request.getRole(), "Role must not be null");
        if (request.getRole() != ChatMessageDTO.RoleType.USER) {
            throw new BizException("Conversation entrypoint only accepts user messages");
        }
    }

    private ConversationTurnContext buildTurnContext(CreateChatMessageRequest request) {
        CreateChatMessageRequest normalizedRequest = normalizeRequest(request);

        GetChatSessionResponse chatSession = chatSessionFacadeService.getChatSession(normalizedRequest.getSessionId());
        if (!normalizedRequest.getAgentId().equals(chatSession.getChatSession().getAgentId())) {
            throw new BizException("Chat session does not belong to the requested agent");
        }

        CreateChatMessageResponse createdMessage = chatMessageFacadeService.createChatMessage(normalizedRequest);
        List<ChatMessageDTO> recentHistory = loadRecentHistory(normalizedRequest.getSessionId());

        log.info("Conversation turn context built: sessionId={}, agentId={}, userMessageId={}, recentHistorySize={}",
                normalizedRequest.getSessionId(),
                normalizedRequest.getAgentId(),
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

    private void dispatchTurn(ConversationTurnContext turnContext) {
        applicationEventPublisher.publishEvent(
                new ChatEvent(
                        turnContext.request().getAgentId(),
                        turnContext.request().getSessionId(),
                        turnContext.createdUserMessage().getChatMessageId(),
                        turnContext.request().getContent(),
                        turnContext.historySize()
                )
        );
    }

    private List<ChatMessageDTO> loadRecentHistory(String sessionId) {
        return chatMessageFacadeService.getChatMessagesBySessionIdRecently(sessionId, RECENT_HISTORY_LIMIT);
    }

    private CreateChatMessageRequest normalizeRequest(CreateChatMessageRequest request) {
        return CreateChatMessageRequest.builder()
                .agentId(request.getAgentId().trim())
                .sessionId(request.getSessionId().trim())
                .role(request.getRole())
                .content(request.getContent().trim())
                .metadata(request.getMetadata())
                .build();
    }
}
