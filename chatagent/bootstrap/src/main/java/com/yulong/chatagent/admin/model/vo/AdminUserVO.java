package com.yulong.chatagent.admin.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
