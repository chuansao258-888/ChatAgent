package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

/** Outcome of replaying dead-letter-queue messages: how many were replayed and the remaining depth. */
@Data
@Builder
public class ReplayDlqMessagesResponse {
    private int replayedCount;
    private long remainingDlqDepth;
    private boolean resetRetryCount;
}
