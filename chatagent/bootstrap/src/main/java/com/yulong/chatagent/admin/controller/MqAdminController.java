package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.admin.application.MqAdminFacadeService;
import com.yulong.chatagent.admin.model.request.ReplayDlqMessagesRequest;
import com.yulong.chatagent.admin.model.response.GetMqOutboxRetryResponse;
import com.yulong.chatagent.admin.model.response.ReplayDlqMessagesResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator endpoints for observing MQ backlog state and replaying DLQ messages.
 */
@RestController
@RequestMapping("/api/admin/mq")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
@ConditionalOnProperty(prefix = "chatagent.mq", name = "enabled", havingValue = "true")
public class MqAdminController {

    private final MqAdminFacadeService mqAdminFacadeService;

    @GetMapping("/outbox/retry")
    public ApiResponse<GetMqOutboxRetryResponse> getOutboxRetryState(
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String idempotencyKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(mqAdminFacadeService.getOutboxRetryState(eventId, idempotencyKey, status, limit));
    }

    @PostMapping("/dlq/replay")
    public ApiResponse<ReplayDlqMessagesResponse> replayDlqMessages(
            @RequestBody(required = false) ReplayDlqMessagesRequest request) {
        return ApiResponse.success(mqAdminFacadeService.replayDlqMessages(request));
    }
}
