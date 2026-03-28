package com.yulong.chatagent.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO representing one persisted rolling summary for a chat session.
 */
@Data
@Builder
public class ChatSessionSummaryDTO {
    private String sessionId;
    private Long lastSeqNo;
    private String summary;
    private Map<String, List<String>> anchoredEntities;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
