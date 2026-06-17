package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

/** Response after an admin creates a user, including the generated initial password. */
@Data
@Builder
public class CreateAdminUserResponse {

    private String userId;

    private String username;

    private String initialPassword;
}
