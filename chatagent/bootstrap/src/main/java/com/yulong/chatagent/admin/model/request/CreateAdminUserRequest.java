package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/** Request body for an admin creating a new user account. */
@Data
public class CreateAdminUserRequest {

    private String username;

    private String role;

    private String avatar;
}
