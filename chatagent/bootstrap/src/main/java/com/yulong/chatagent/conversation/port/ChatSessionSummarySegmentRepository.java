package com.yulong.chatagent.conversation.port;

import com.yulong.chatagent.support.dto.ChatSessionSummarySegmentDTO;

import java.util.List;

/**
 * Port interface for L2 summary segment persistence.
 */
public interface ChatSessionSummarySegmentRepository {

    /**
     * Inserts a new segment. Returns true on success.
     * The unique constraint on (session_id, seq_start_no, seq_end_no) provides idempotency.
     */
    boolean insert(ChatSessionSummarySegmentDTO segment);

    /**
     * Returns active segments for a session, ordered by seq_end_no descending (newest first).
     */
    List<ChatSessionSummarySegmentDTO> findActiveBySessionId(String sessionId);

    /**
     * Returns active segments for a session, ordered by seq_start_no ascending (oldest first).
     */
    List<ChatSessionSummarySegmentDTO> findActiveBySessionIdOrdered(String sessionId);

    /**
     * Deletes all segments for a session.
     */
    boolean deleteBySessionId(String sessionId);
}
