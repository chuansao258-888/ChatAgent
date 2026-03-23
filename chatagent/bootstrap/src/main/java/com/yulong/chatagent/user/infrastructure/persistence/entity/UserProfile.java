package com.yulong.chatagent.user.infrastructure.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis persistence entity mapped to table {@code user_profile}.
 */
@Data
@Builder
public class UserProfile {

    private String userId;

    private String summary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
