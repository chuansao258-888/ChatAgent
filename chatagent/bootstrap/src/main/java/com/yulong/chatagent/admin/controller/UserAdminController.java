package com.yulong.chatagent.admin.controller;

import com.yulong.chatagent.access.RequireRole;
import com.yulong.chatagent.access.UserRole;
import com.yulong.chatagent.admin.application.UserAdminFacadeService;
import com.yulong.chatagent.admin.model.request.CreateAdminUserRequest;
import com.yulong.chatagent.admin.model.request.UpdateAdminUserRequest;
import com.yulong.chatagent.admin.model.request.UpdateAdminUserStatusRequest;
import com.yulong.chatagent.admin.model.response.CreateAdminUserResponse;
import com.yulong.chatagent.admin.model.response.GetAdminUsersResponse;
import com.yulong.chatagent.admin.model.response.ResetAdminUserPasswordResponse;
import com.yulong.chatagent.model.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator endpoints for user management.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@RequireRole(UserRole.ADMIN)
public class UserAdminController {

    private final UserAdminFacadeService userAdminFacadeService;

    @GetMapping
    public ApiResponse<GetAdminUsersResponse> getUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(userAdminFacadeService.getUsers(page, size, keyword, status));
    }

    @PostMapping
    public ApiResponse<CreateAdminUserResponse> createUser(@RequestBody CreateAdminUserRequest request) {
        return ApiResponse.success(userAdminFacadeService.createUser(request));
    }

    @PutMapping("/{userId}")
    public ApiResponse<Void> updateUser(@PathVariable String userId,
                                        @RequestBody UpdateAdminUserRequest request) {
        userAdminFacadeService.updateUser(userId, request);
        return ApiResponse.success();
    }

    @PutMapping("/{userId}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable String userId,
                                              @RequestBody UpdateAdminUserStatusRequest request) {
        userAdminFacadeService.updateUserStatus(userId, request);
        return ApiResponse.success();
    }

    @PutMapping("/{userId}/password/reset")
    public ApiResponse<ResetAdminUserPasswordResponse> resetPassword(@PathVariable String userId) {
        return ApiResponse.success(userAdminFacadeService.resetPassword(userId));
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable String userId) {
        userAdminFacadeService.deleteUser(userId);
        return ApiResponse.success();
    }
}
