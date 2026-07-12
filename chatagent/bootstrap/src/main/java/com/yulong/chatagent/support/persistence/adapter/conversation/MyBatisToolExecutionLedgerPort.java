package com.yulong.chatagent.support.persistence.adapter.conversation;

import com.yulong.chatagent.agent.tools.ToolExecutionLedgerPort;
import com.yulong.chatagent.support.persistence.entity.ToolExecutionJournal;
import com.yulong.chatagent.support.persistence.mapper.ToolExecutionJournalMapper;
import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MyBatis-backed implementation of {@link ToolExecutionLedgerPort}.
 * <p>
 * Hides the journal mapper and transaction boundary from the coordinator. Terminal CAS and the
 * paired-response reference write share one {@link Transactional} method, so a publish failure
 * after commit can never leave a database orphan (ARRB-DEC-010 / ARRB-AC-009).
 */
@Repository
public class MyBatisToolExecutionLedgerPort implements ToolExecutionLedgerPort {

    private final ToolExecutionJournalMapper mapper;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageRepository chatMessageRepository;

    @Autowired
    public MyBatisToolExecutionLedgerPort(ToolExecutionJournalMapper mapper,
                                          ChatMessageFacadeService chatMessageFacadeService,
                                          ChatMessageRepository chatMessageRepository) {
        this.mapper = mapper;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageRepository = chatMessageRepository;
    }

    public MyBatisToolExecutionLedgerPort(ToolExecutionJournalMapper mapper) {
        this(mapper, null, null);
    }

    public MyBatisToolExecutionLedgerPort(ToolExecutionJournalMapper mapper,
                                          ChatMessageFacadeService chatMessageFacadeService) {
        this(mapper, chatMessageFacadeService, null);
    }

    @Override
    public Optional<JournalEntry> prepare(JournalEntry entry) {
        ToolExecutionJournal entity = toEntity(entry);
        if (entity.getState() == null) {
            entity.setState("PREPARED");
        }
        if (entity.getAttempt() == null) {
            entity.setAttempt(1);
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException duplicate) {
            // execution key already present: another process owns this dispatch.
            return Optional.empty();
        }
        return Optional.of(toEntry(mapper.selectByExecutionKey(entry.executionKey())));
    }

    @Override
    public boolean tryDispatch(String executionKey, String expectedState, int attempt,
                               LocalDateTime dispatchedAt) {
        return mapper.casToDispatching(executionKey, expectedState, attempt, dispatchedAt) > 0;
    }

    @Override
    public boolean prepareRetry(String executionKey, int expectedAttempt, int nextAttempt,
                                String safeErrorCode) {
        return mapper.casToRetryPrepared(
                executionKey, expectedAttempt, nextAttempt, safeErrorCode) == 1;
    }

    @Override
    @Transactional
    public boolean commitTerminal(String executionKey, String expectedState, TerminalUpdate update) {
        return mapper.casToTerminal(
                executionKey,
                expectedState,
                update.newState(),
                update.expectedAttempt(),
                update.responseMessageId(),
                update.responseHash(),
                update.safeErrorCode()) > 0;
    }

    @Override
    @Transactional
    public PersistedToolResponse commitTerminalResponse(String executionKey,
                                                        String expectedState,
                                                        TerminalUpdate update,
                                                        ToolResponseCommitRequest response) {
        if (chatMessageFacadeService == null) {
            throw new IllegalStateException("Chat message persistence is required for atomic tool commits");
        }
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.TOOL)
                .content(response.response().responseData())
                .sessionId(response.sessionId())
                .turnId(response.turnId())
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(response.response())
                        .internal(response.internal() ? Boolean.TRUE : null)
                        .deepThinkPhase(response.deepThinkPhase())
                        .planStepId(response.planStepId())
                        .build())
                .build();
        CreateChatMessageResponse created = chatMessageFacadeService.createChatMessage(dto);
        int updated = mapper.casToTerminal(
                executionKey,
                expectedState,
                update.newState(),
                update.expectedAttempt(),
                created.getChatMessageId(),
                update.responseHash(),
                update.safeErrorCode());
        if (updated != 1) {
            throw new ToolTerminalCommitConflictException(executionKey);
        }
        return new PersistedToolResponse(created.getChatMessageId(), created.getTurnSeq(), response);
    }

    static final class ToolTerminalCommitConflictException extends RuntimeException {
        ToolTerminalCommitConflictException(String executionKey) {
            super("Tool terminal commit conflict: " + executionKey);
        }
    }

    @Override
    public Optional<JournalEntry> findByExecutionKey(String executionKey) {
        return Optional.ofNullable(mapper.selectByExecutionKey(executionKey))
                .map(MyBatisToolExecutionLedgerPort::toEntry);
    }

    @Override
    public Optional<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse>
    loadCommittedResponse(String executionKey) {
        if (chatMessageRepository == null) {
            return Optional.empty();
        }
        ToolExecutionJournal journal = mapper.selectByExecutionKey(executionKey);
        if (journal == null || !"SUCCEEDED".equals(journal.getState())
                || journal.getResponseMessageId() == null) {
            return Optional.empty();
        }
        ChatMessageDTO message = chatMessageRepository.findById(journal.getResponseMessageId());
        if (message == null || message.getMetadata() == null
                || message.getMetadata().getToolResponse() == null) {
            return Optional.empty();
        }
        return Optional.of(message.getMetadata().getToolResponse());
    }

    private static ToolExecutionJournal toEntity(JournalEntry entry) {
        ToolExecutionJournal entity = new ToolExecutionJournal();
        entity.setId(entry.id());
        entity.setExecutionKey(entry.executionKey());
        entity.setSessionId(entry.sessionId());
        entity.setTurnId(entry.turnId());
        entity.setApprovalId(entry.approvalId());
        entity.setAssistantMessageId(entry.assistantMessageId());
        entity.setToolCallId(entry.toolCallId());
        entity.setToolName(entry.toolName());
        entity.setArgumentHash(entry.argumentHash());
        entity.setEffectClass(entry.effectClass());
        entity.setAttempt(entry.attempt());
        entity.setState(entry.state());
        entity.setSafeErrorCode(entry.safeErrorCode());
        entity.setResponseMessageId(entry.responseMessageId());
        entity.setResponseHash(entry.responseHash());
        entity.setDispatchedAt(entry.dispatchedAt());
        entity.setCallDeadlineMs(entry.callDeadlineMs());
        entity.setCreatedAt(entry.createdAt());
        entity.setUpdatedAt(entry.updatedAt());
        return entity;
    }

    private static JournalEntry toEntry(ToolExecutionJournal entity) {
        return new JournalEntry(
                entity.getId(),
                entity.getExecutionKey(),
                entity.getSessionId(),
                entity.getTurnId(),
                entity.getApprovalId(),
                entity.getAssistantMessageId(),
                entity.getToolCallId(),
                entity.getToolName(),
                entity.getArgumentHash(),
                entity.getEffectClass(),
                entity.getAttempt() == null ? 1 : entity.getAttempt(),
                entity.getState(),
                entity.getSafeErrorCode(),
                entity.getResponseMessageId(),
                entity.getResponseHash(),
                entity.getDispatchedAt(),
                entity.getCallDeadlineMs(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
