package com.yulong.chatagent.conversation.application;

import com.yulong.chatagent.conversation.event.ChatEvent;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.exception.BizException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * Default turn orchestrator for the current conversation workflow.
 * <p>
 * The first version keeps the orchestration intentionally small: validate the
 * request, ensure the target session exists, persist the user message through
 * the message facade, and trigger asynchronous downstream processing.
 */
@Service
public class ConversationOrchestratorServiceImpl implements ConversationOrchestratorService {
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
        // Guard the orchestration boundary before touching persistence or events.
        Assert.notNull(request, "CreateChatMessageRequest must not be null");
        Assert.hasText(request.getAgentId(), "AgentId must not be empty");
        Assert.hasText(request.getSessionId(), "SessionId must not be empty");
        Assert.hasText(request.getContent(), "Content must not be empty");

        // Reuse the session facade as the source of truth for existence checks.
        GetChatSessionResponse chatSession = chatSessionFacadeService.getChatSession(request.getSessionId());
        if (!request.getAgentId().equals(chatSession.getChatSession().getAgentId())) {
            throw new BizException("Chat session does not belong to the requested agent");
        }

        CreateChatMessageResponse createdMessage = chatMessageFacadeService.createChatMessage(request);

        // The first version only persists the user turn and starts asynchronous processing.
        applicationEventPublisher.publishEvent(
                new ChatEvent(request.getAgentId(), request.getSessionId(), request.getContent())
        );
        return createdMessage;
    }
}
