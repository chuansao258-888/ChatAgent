package com.yulong.chatagent.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * Authenticated user snapshot stored in {@link UserContext} for the duration
 * of a single request.
 *
 * <p>This type is intentionally smaller than the full user record and keeps
 * only the fields needed during authorization and response shaping.</p>
 */
public class LoginUser {

    private String userId;

    private String username;

    private String role;

    private String avatar;

    private String status;
}
