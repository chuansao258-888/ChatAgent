package com.yulong.chatagent.support.persistence.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @TableName t_user
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
