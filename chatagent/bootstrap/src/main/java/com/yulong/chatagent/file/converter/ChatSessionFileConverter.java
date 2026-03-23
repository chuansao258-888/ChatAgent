package com.yulong.chatagent.file.converter;

import com.yulong.chatagent.file.model.vo.ChatSessionFileVO;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Converts chat-session file DTOs into view objects.
 */
@Component
public class ChatSessionFileConverter {

    public ChatSessionFileVO toVO(ChatSessionFileDTO dto) {
        Assert.notNull(dto, "ChatSessionFileDTO cannot be null");
        return ChatSessionFileVO.builder()
                .id(dto.getId())
                .filename(dto.getFilename())
                .originalFilename(dto.getOriginalFilename())
                .mimeType(dto.getMimeType())
                .sizeBytes(dto.getSizeBytes())
                .status(dto.getStatus())
                .parseStatus(dto.getParseStatus())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
