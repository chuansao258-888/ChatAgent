package com.yulong.chatagent.agent.user.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {

    private String accessToken;

    private String refreshToken;

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
