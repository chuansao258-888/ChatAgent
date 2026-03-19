package com.yulong.chatagent.user.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/**
 * Application-specific JWT payload used by the access-token service.
 *
 * <p>This type deliberately contains only the small set of fields needed
 * during request authentication and authorization.</p>
 */
public class JwtClaims {
    private String userId;
    private String username;
    private String role;
}
