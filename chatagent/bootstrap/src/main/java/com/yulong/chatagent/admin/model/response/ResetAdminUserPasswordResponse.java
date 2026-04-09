package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResetAdminUserPasswordResponse {

    private String userId;

    private String newPassword;
}
