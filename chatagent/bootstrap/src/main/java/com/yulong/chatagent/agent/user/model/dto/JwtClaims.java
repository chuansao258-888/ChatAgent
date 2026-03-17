package com.yulong.chatagent.agent.user.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JwtClaims {
    private String userId;
    private String username;
    private String role;
}
