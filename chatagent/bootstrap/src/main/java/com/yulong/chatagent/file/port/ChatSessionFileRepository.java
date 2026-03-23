package com.yulong.chatagent.file.port;

import com.yulong.chatagent.support.dto.ChatSessionFileDTO;

import java.util.List;

/**
 * Persistence port for files stored directly on chat sessions.
 */
public interface ChatSessionFileRepository {

    List<ChatSessionFileDTO> findBySessionId(String sessionId);

    ChatSessionFileDTO findById(String sessionFileId);

    boolean save(ChatSessionFileDTO chatSessionFile);

    boolean update(ChatSessionFileDTO chatSessionFile);

    boolean deleteById(String sessionFileId);
}
