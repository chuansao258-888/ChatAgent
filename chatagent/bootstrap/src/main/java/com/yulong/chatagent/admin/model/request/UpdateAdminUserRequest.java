package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/** Request body for an admin updating a user's role and avatar. */
@Data
public class UpdateAdminUserRequest {

    private String role;

    private String avatar;
}
