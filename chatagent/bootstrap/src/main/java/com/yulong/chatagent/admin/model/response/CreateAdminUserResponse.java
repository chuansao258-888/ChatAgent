package com.yulong.chatagent.admin.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateAdminUserResponse {

    private String userId;

    private String username;

    private String initialPassword;
}
