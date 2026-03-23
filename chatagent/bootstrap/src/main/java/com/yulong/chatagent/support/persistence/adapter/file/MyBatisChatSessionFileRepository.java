package com.yulong.chatagent.support.persistence.adapter.file;

import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.persistence.entity.ChatSessionFile;
import com.yulong.chatagent.support.persistence.mapper.ChatSessionFileMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis implementation of the chat-session attachment repository port.
 */
@Repository
public class MyBatisChatSessionFileRepository implements ChatSessionFileRepository {

    private final ChatSessionFileMapper chatSessionFileMapper;

    public MyBatisChatSessionFileRepository(ChatSessionFileMapper chatSessionFileMapper) {
        this.chatSessionFileMapper = chatSessionFileMapper;
    }

    @Override
    public List<ChatSessionFileDTO> findBySessionId(String sessionId) {
        List<ChatSessionFileDTO> result = new ArrayList<>();
        for (ChatSessionFile entity : chatSessionFileMapper.selectBySessionId(sessionId)) {
            result.add(toDTO(entity));
        }
        return result;
    }

    @Override
    public ChatSessionFileDTO findById(String sessionFileId) {
        return toDTO(chatSessionFileMapper.selectById(sessionFileId));
    }

    @Override
    public boolean save(ChatSessionFileDTO chatSessionFile) {
        return chatSessionFileMapper.insert(toEntity(chatSessionFile)) > 0;
    }

    @Override
    public boolean update(ChatSessionFileDTO chatSessionFile) {
        return chatSessionFileMapper.updateById(toEntity(chatSessionFile)) > 0;
    }

    @Override
    public boolean deleteById(String sessionFileId) {
        return chatSessionFileMapper.deleteById(sessionFileId) > 0;
    }

    private ChatSessionFileDTO toDTO(ChatSessionFile entity) {
        if (entity == null) {
            return null;
        }
        return ChatSessionFileDTO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .filename(entity.getFilename())
                .originalFilename(entity.getOriginalFilename())
                .mimeType(entity.getMimeType())
                .sizeBytes(entity.getSizeBytes())
                .storagePath(entity.getStoragePath())
                .status(entity.getStatus())
                .parseStatus(entity.getParseStatus())
                .metadata(entity.getMetadata())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private ChatSessionFile toEntity(ChatSessionFileDTO dto) {
        return ChatSessionFile.builder()
                .id(dto.getId())
                .sessionId(dto.getSessionId())
                .filename(dto.getFilename())
                .originalFilename(dto.getOriginalFilename())
                .mimeType(dto.getMimeType())
                .sizeBytes(dto.getSizeBytes())
                .storagePath(dto.getStoragePath())
                .status(dto.getStatus())
                .parseStatus(dto.getParseStatus())
                .metadata(dto.getMetadata())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
