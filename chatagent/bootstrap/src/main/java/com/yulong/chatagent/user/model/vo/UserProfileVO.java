package com.yulong.chatagent.user.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileVO {
    private String userId;
    private String summary;
    private LocalDateTime updatedAt;
}
