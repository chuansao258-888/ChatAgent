package com.yulong.chatagent.conversation.summary;

import java.util.List;

/**
 * Minimal summarizable view of one atomic conversation turn.
 *
 * @param turnId logical turn boundary
 * @param startSeqNo first message seq_no in the turn
 * @param endSeqNo last message seq_no in the turn
 * @param userMessages raw user utterances kept for grounding
 * @param assistantConclusion final assistant answer without tool-calls
 */
public record AtomicConversationTurn(
        String turnId,
        long startSeqNo,
        long endSeqNo,
        List<String> userMessages,
        String assistantConclusion
) {

    public boolean hasSummarizableContent() {
        return (userMessages != null && !userMessages.isEmpty())
                || (assistantConclusion != null && !assistantConclusion.isBlank());
    }
}
