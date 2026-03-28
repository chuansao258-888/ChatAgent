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

/**
 * REST controller for chat session management endpoints.
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatSessionController {

    private final ChatSessionFacadeService chatSessionFacadeService;

    /**
     * Returns all chat sessions.
     *
     * @return chat sessions response
     */
    @GetMapping("/chat-sessions")
    public ApiResponse<GetChatSessionsResponse> getChatSessions() {
        return ApiResponse.success(chatSessionFacadeService.getChatSessions());
    }

    /**
     * Returns one chat session by identifier.
     *
     * @param chatSessionId chat session identifier
     * @return chat session response
     */
    @GetMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<GetChatSessionResponse> getChatSession(@PathVariable String chatSessionId) {
        return ApiResponse.success(chatSessionFacadeService.getChatSession(chatSessionId));
    }

    /**
     * Creates a new chat session.
     *
     * @param request create session request
     * @return created session response
     */
    @PostMapping("/chat-sessions")
    public ApiResponse<CreateChatSessionResponse> createChatSession(@RequestBody CreateChatSessionRequest request) {
        return ApiResponse.success(chatSessionFacadeService.createChatSession(request));
    }

    /**
     * Deletes a chat session.
     *
     * @param chatSessionId chat session identifier
     * @return empty success response
     */
    @DeleteMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> deleteChatSession(@PathVariable String chatSessionId) {
        chatSessionFacadeService.deleteChatSession(chatSessionId);
        return ApiResponse.success();
    }

    /**
     * Updates mutable metadata of a chat session.
     *
     * @param chatSessionId chat session identifier
     * @param request update request payload
     * @return empty success response
     */
    @PatchMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> updateChatSession(@PathVariable String chatSessionId,
                                               @RequestBody UpdateChatSessionRequest request) {
        chatSessionFacadeService.updateChatSession(chatSessionId, request);
        return ApiResponse.success();
    }
}
