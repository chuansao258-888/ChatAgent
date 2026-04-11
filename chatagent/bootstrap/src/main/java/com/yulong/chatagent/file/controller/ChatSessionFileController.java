package com.yulong.chatagent.file.controller;

import com.yulong.chatagent.file.application.ChatSessionFileFacadeService;
import com.yulong.chatagent.file.model.response.UploadChatSessionFileResponse;
import com.yulong.chatagent.file.model.vo.ChatSessionFileVO;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for chat-session file attachments.
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatSessionFileController {

    private final ChatSessionFileFacadeService chatSessionFileFacadeService;

    @GetMapping("/chat-sessions/{sessionId}/files")
    public ApiResponse<ChatSessionFileVO[]> getChatSessionFiles(@PathVariable String sessionId) {
        return ApiResponse.success(chatSessionFileFacadeService.getChatSessionFiles(sessionId));
    }

    @PostMapping("/chat-sessions/{sessionId}/files/upload")
    public ApiResponse<UploadChatSessionFileResponse> uploadChatSessionFile(@PathVariable String sessionId,
                                                                            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(chatSessionFileFacadeService.uploadFile(sessionId, file));
    }

    @DeleteMapping("/chat-sessions/{sessionId}/files/{sessionFileId}")
    public ApiResponse<Void> detachFile(@PathVariable String sessionId, @PathVariable String sessionFileId) {
        chatSessionFileFacadeService.detachFile(sessionId, sessionFileId);
        return ApiResponse.success();
    }
}
