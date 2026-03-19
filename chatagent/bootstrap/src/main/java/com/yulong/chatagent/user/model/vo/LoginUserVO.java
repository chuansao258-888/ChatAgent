package com.yulong.chatagent.user.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
/**
 * API view of the currently authenticated user.
 */
public class LoginUserVO {

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
