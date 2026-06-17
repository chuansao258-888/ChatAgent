package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Admin-facing view of a user account. */
@Data
@Builder
public class AdminUserVO {

    private String id;

    private String username;

    private String role;

    private String avatar;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
