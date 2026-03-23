package com.yulong.chatagent.file.model.response;

import com.yulong.chatagent.file.model.vo.ChatSessionFileVO;
import lombok.Builder;
import lombok.Data;

/**
 * Response payload containing files attached to one chat session.
 */
@Data
@Builder
public class GetChatSessionFilesResponse {
    private ChatSessionFileVO[] files;
}
