package com.yulong.chatagent.admin.model.request;

import lombok.Data;

/** Request body for an admin updating a user's account status. */
@Data
public class UpdateAdminUserStatusRequest {

    private String status;
}
