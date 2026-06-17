package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/** Request body for replaying dead-letter-queue messages, optionally capping the count and resetting retry counters. */
@Data
public class ReplayDlqMessagesRequest {
    private Integer limit;
    private Boolean resetRetryCount;
}
