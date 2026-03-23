package com.yulong.chatagent.file.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * Response payload returned after uploading one file into a chat session.
 */
@Data
@Builder
public class UploadChatSessionFileResponse {
    private String sessionFileId;
    private String sessionId;
}
