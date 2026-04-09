package com.yulong.chatagent.user.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Application-level representation of persistent user profile memory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {

    private String userId;

    private String summary;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
