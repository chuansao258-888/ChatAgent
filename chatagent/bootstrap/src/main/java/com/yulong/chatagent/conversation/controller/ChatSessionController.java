package com.yulong.chatagent.conversation.controller;

import com.yulong.chatagent.conversation.application.ChatSessionFacadeService;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.conversation.model.request.CreateChatSessionRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatSessionRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionResponse;
import com.yulong.chatagent.conversation.model.response.GetChatSessionsResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatSessionController {

    private final ChatSessionFacadeService chatSessionFacadeService;

    @GetMapping("/chat-sessions")
    public ApiResponse<GetChatSessionsResponse> getChatSessions() {
        return ApiResponse.success(chatSessionFacadeService.getChatSessions());
    }

    @GetMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<GetChatSessionResponse> getChatSession(@PathVariable String chatSessionId) {
        return ApiResponse.success(chatSessionFacadeService.getChatSession(chatSessionId));
    }

    @GetMapping("/chat-sessions/agent/{agentId}")
    public ApiResponse<GetChatSessionsResponse> getChatSessionsByAgentId(@PathVariable String agentId) {
        return ApiResponse.success(chatSessionFacadeService.getChatSessionsByAgentId(agentId));
    }

    @PostMapping("/chat-sessions")
    public ApiResponse<CreateChatSessionResponse> createChatSession(@RequestBody CreateChatSessionRequest request) {
        return ApiResponse.success(chatSessionFacadeService.createChatSession(request));
    }

    @DeleteMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> deleteChatSession(@PathVariable String chatSessionId) {
        chatSessionFacadeService.deleteChatSession(chatSessionId);
        return ApiResponse.success();
    }

    @PatchMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> updateChatSession(@PathVariable String chatSessionId,
                                               @RequestBody UpdateChatSessionRequest request) {
        chatSessionFacadeService.updateChatSession(chatSessionId, request);
        return ApiResponse.success();
    }
}

