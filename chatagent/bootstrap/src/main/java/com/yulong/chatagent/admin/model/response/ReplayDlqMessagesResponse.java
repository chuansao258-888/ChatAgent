package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReplayDlqMessagesResponse {
    private int replayedCount;
    private long remainingDlqDepth;
    private boolean resetRetryCount;
}
