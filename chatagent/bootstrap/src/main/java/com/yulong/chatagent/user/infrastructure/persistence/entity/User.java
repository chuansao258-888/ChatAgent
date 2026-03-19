package com.yulong.chatagent.user.infrastructure.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MyBatis persistence entity mapped to table {@code t_user}.
 */
@Data
@Builder
public class User {

    private String id;

    private String username;

    private String passwordHash;

    private String role;

    private String avatar;

    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
