package com.yulong.chatagent.file.application;

import com.yulong.chatagent.file.model.response.GetChatSessionFilesResponse;
import com.yulong.chatagent.file.model.response.UploadChatSessionFileResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * Facade for attaching files to chat sessions.
 */
public interface ChatSessionFileFacadeService {

    GetChatSessionFilesResponse getChatSessionFiles(String sessionId);

    UploadChatSessionFileResponse uploadFile(String sessionId, MultipartFile file);

    void detachFile(String sessionId, String sessionFileId);
}
