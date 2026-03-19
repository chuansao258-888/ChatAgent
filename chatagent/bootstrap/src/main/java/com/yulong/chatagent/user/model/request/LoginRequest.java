package com.yulong.chatagent.user.model.request;

import lombok.Data;

@Data
/**
 * Request body for username/password login.
 */
public class LoginRequest {

    private String username;

    private String password;
}
