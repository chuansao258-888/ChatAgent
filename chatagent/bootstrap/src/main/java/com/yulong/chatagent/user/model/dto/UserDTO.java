package com.yulong.chatagent.user.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * Application-level representation of a user account.
 *
 * <p>This DTO is shared across the auth service, repository port, and
 * converter layer, but is not returned directly to external clients.</p>
 */
public class UserDTO {

    private String id;

    private String username;

    private String passwordHash;

    private String role;

    private String avatar;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
