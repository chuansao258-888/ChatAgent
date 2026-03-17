package com.yulong.chatagent.context;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginUser {

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
