package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts atomic turns from persisted chat messages using turn_id boundaries.
 */
@Component
public class TurnBasedContextExtractor {

    private final SummaryWatermarkService summaryWatermarkService;
    private final ChatMessageRepository chatMessageRepository;

    public TurnBasedContextExtractor(SummaryWatermarkService summaryWatermarkService,
                                     ChatMessageRepository chatMessageRepository) {
        this.summaryWatermarkService = summaryWatermarkService;
        this.chatMessageRepository = chatMessageRepository;
    }

    public long countTurns(String sessionId) {
        return chatMessageRepository.countTurnsBySessionId(sessionId);
    }

    public List<AtomicConversationTurn> extractPendingTurns(String sessionId, long anchorSeqNo) {
        List<ChatMessageDTO> pendingMessages = summaryWatermarkService.loadPendingMessages(sessionId, anchorSeqNo);
        if (pendingMessages.isEmpty()) {
            return List.of();
        }

        Map<String, List<ChatMessageDTO>> grouped = new LinkedHashMap<>();
        for (ChatMessageDTO message : pendingMessages) {
            if (!StringUtils.hasText(message.getTurnId())) {
                continue;
            }
            grouped.computeIfAbsent(message.getTurnId(), ignored -> new ArrayList<>()).add(message);
        }

        List<AtomicConversationTurn> turns = new ArrayList<>();
        for (Map.Entry<String, List<ChatMessageDTO>> entry : grouped.entrySet()) {
            AtomicConversationTurn turn = toTurn(entry.getKey(), entry.getValue());
            if (turn != null && turn.hasSummarizableContent()) {
                turns.add(turn);
            }
        }
        return turns;
    }

    private AtomicConversationTurn toTurn(String turnId, List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        long startSeqNo = messages.get(0).getSeqNo() == null ? 0L : messages.get(0).getSeqNo();
        long endSeqNo = startSeqNo;
        List<String> userMessages = new ArrayList<>();
        String assistantConclusion = null;

        for (ChatMessageDTO message : messages) {
            if (message.getSeqNo() != null) {
                endSeqNo = Math.max(endSeqNo, message.getSeqNo());
            }
            if (message.getRole() == ChatMessageDTO.RoleType.USER && StringUtils.hasText(message.getContent())) {
                userMessages.add(message.getContent().trim());
                continue;
            }
            if (message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                    && StringUtils.hasText(message.getContent())
                    && !hasToolCalls(message)) {
                assistantConclusion = message.getContent().trim();
            }
        }

        return new AtomicConversationTurn(turnId, startSeqNo, endSeqNo, List.copyOf(userMessages), assistantConclusion);
    }

    private boolean hasToolCalls(ChatMessageDTO message) {
        ChatMessageDTO.MetaData metadata = message.getMetadata();
        return metadata != null && metadata.getToolCalls() != null && !metadata.getToolCalls().isEmpty();
    }
}
