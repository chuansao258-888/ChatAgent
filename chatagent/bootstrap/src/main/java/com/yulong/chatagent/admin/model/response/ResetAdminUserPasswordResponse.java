package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

/** Response after an admin resets a user's password, including the new password. */
@Data
@Builder
public class ResetAdminUserPasswordResponse {

    private String userId;

    private String newPassword;
}
