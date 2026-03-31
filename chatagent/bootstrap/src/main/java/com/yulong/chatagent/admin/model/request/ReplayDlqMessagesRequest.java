package com.yulong.chatagent.admin.model.request;

import lombok.Data;

@Data
public class ReplayDlqMessagesRequest {
    private Integer limit;
    private Boolean resetRetryCount;
}
