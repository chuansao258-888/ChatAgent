package com.yulong.chatagent.user.model.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
/**
 * Unified response payload for register, login, and refresh operations.
 *
 * <p>The refresh token is present in server-side code so the controller can
 * write it into a cookie, but it is hidden from JSON serialization.</p>
 */
public class LoginResponse {

    private String accessToken;

    @JsonIgnore
    private String refreshToken;

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
