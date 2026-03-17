package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @TableName t_refresh_token
 */
@Data
@Builder
public class RefreshToken {

    private String id;

    private String userId;

    private String tokenHash;

    private LocalDateTime expiresAt;

    private Boolean revoked;

    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
