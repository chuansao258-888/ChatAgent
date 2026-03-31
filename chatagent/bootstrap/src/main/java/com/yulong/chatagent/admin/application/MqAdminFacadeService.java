package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.ReplayDlqMessagesRequest;
import com.yulong.chatagent.admin.model.response.GetMqOutboxRetryResponse;
import com.yulong.chatagent.admin.model.response.ReplayDlqMessagesResponse;

/**
 * Administrator operations for observing MQ backlog state and replaying DLQ work.
 */
public interface MqAdminFacadeService {

    GetMqOutboxRetryResponse getOutboxRetryState(String eventId,
                                                 String idempotencyKey,
                                                 String status,
                                                 Integer limit);

    ReplayDlqMessagesResponse replayDlqMessages(ReplayDlqMessagesRequest request);
}
