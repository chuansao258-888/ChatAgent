package com.yulong.chatagent.admin.model.request;

import lombok.Data;

@Data
public class CreateAdminUserRequest {

    private String username;

    private String role;

    private String avatar;
}
