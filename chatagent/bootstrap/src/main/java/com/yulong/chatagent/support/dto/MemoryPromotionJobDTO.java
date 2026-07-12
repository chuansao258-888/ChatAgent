package com.yulong.chatagent.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Durable work item for promoting one committed L2 range into L3 memory. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryPromotionJobDTO {
    private String id;
    private String userId;
    private String sessionId;
    private Long seqStartNo;
    private Long seqEndNo;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime processingStartedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
