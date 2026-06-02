package com.yulong.chatagent.conversation.controller;

import com.yulong.chatagent.conversation.application.ChatMessageFacadeService;
import com.yulong.chatagent.conversation.application.ConversationOrchestratorService;
import com.yulong.chatagent.conversation.application.SessionConcurrencyGuard;
import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.conversation.model.request.CreateChatMessageRequest;
import com.yulong.chatagent.conversation.model.request.UpdateChatMessageRequest;
import com.yulong.chatagent.conversation.model.response.CreateChatMessageResponse;
import com.yulong.chatagent.conversation.model.vo.ChatMessageVO;
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
 * 聊天消息 REST 入口。
 * <p>
 * 这个 Controller 表面上是“消息 CRUD”，但真正重要的是
 * {@code POST /api/chat-messages} 这一条入口：
 * <ul>
 *     <li>它不是简单保存一条 USER 消息；</li>
 *     <li>它会启动一次完整的 turn 编排流程；</li>
 *     <li>后续可能继续触发意图准备、异步事件派发和 Agent runtime 执行。</li>
 * </ul>
 * 因此读会话编排主线时，这个类通常是第一个起点。
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatMessageController {

    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ConversationOrchestratorService conversationOrchestratorService;
    private final SessionConcurrencyGuard sessionConcurrencyGuard;

    /**
     * 查询指定会话的完整消息历史（过滤 internal trace 消息）。
     *
     * @param sessionId 会话 ID
     * @return 消息历史（不含 DeepThink 内部 trace）
     */
    @GetMapping("/chat-messages/session/{sessionId}")
    public ApiResponse<ChatMessageVO[]> getChatMessagesBySessionId(@PathVariable String sessionId) {
        return ApiResponse.success(chatMessageFacadeService.getChatMessagesBySessionId(sessionId));
    }

    /**
     * 创建用户消息，并进入“单轮对话”编排流程。
     * <p>
     * 这里会先拿会话级锁，避免同一个 session 同时进入多轮 Agent 执行，
     * 造成消息顺序、短期记忆和 SSE 状态错乱。
     *
     * @param request 用户消息创建请求
     * @return 已创建的用户消息 ID 等信息
     */
    @PostMapping("/chat-messages")
    public ApiResponse<CreateChatMessageResponse> createChatMessage(@RequestBody CreateChatMessageRequest request) {
        // 入口锁只保护“开始一轮 turn”这个动作，避免同一个 session
        // 在极短时间内并发进入两次编排，导致消息顺序、记忆窗口和 SSE 状态混乱。
        try (SessionConcurrencyGuard.SessionLock ignored = sessionConcurrencyGuard.acquire(request.getSessionId())) {
            return ApiResponse.success(conversationOrchestratorService.handleUserTurn(request));
        }
    }

    /**
     * Deletes a chat message.
     *
     * @param chatMessageId target message identifier
     * @return empty success response
     */
    @DeleteMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> deleteChatMessage(@PathVariable String chatMessageId) {
        chatMessageFacadeService.deleteChatMessage(chatMessageId);
        return ApiResponse.success();
    }

    /**
     * Updates a chat message.
     *
     * @param chatMessageId target message identifier
     * @param request update payload
     * @return empty success response
     */
    @PatchMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> updateChatMessage(@PathVariable String chatMessageId,
                                               @RequestBody UpdateChatMessageRequest request) {
        chatMessageFacadeService.updateChatMessage(chatMessageId, request);
        return ApiResponse.success();
    }
}
