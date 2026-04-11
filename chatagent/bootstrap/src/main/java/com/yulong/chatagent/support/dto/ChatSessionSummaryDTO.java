package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO representing one persisted rolling summary for a chat session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionSummaryDTO {
    private String sessionId;
    private Long lastSeqNo;
    private String summary;
    private Map<String, List<String>> anchoredEntities;
    /** Raw JSON string mapped by MyBatis for the {@code anchored_entities} column. */
    private String anchoredEntitiesJson;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
