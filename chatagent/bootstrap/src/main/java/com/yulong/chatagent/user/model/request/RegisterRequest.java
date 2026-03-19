package com.yulong.chatagent.user.model.request;

import lombok.Data;

@Data
/**
 * Request body for account registration.
 */
public class RegisterRequest {

    private String username;

    private String password;
}
