package com.yulong.chatagent.admin.application;

import com.yulong.chatagent.admin.model.request.CreateAdminUserRequest;
import com.yulong.chatagent.admin.model.request.UpdateAdminUserRequest;
import com.yulong.chatagent.admin.model.request.UpdateAdminUserStatusRequest;
import com.yulong.chatagent.admin.model.response.CreateAdminUserResponse;
import com.yulong.chatagent.admin.model.response.GetAdminUsersResponse;
import com.yulong.chatagent.admin.model.response.ResetAdminUserPasswordResponse;

public interface UserAdminFacadeService {

    GetAdminUsersResponse getUsers(int page, int size, String keyword, String status);

    CreateAdminUserResponse createUser(CreateAdminUserRequest request);

    void updateUser(String userId, UpdateAdminUserRequest request);

    void updateUserStatus(String userId, UpdateAdminUserStatusRequest request);

    ResetAdminUserPasswordResponse resetPassword(String userId);

    void deleteUser(String userId);
}
