package com.yulong.chatagent.user.controller;

import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.user.application.UserProfileService;
import com.yulong.chatagent.user.model.dto.UserProfileDTO;
import com.yulong.chatagent.user.model.request.UpdateUserProfileRequest;
import com.yulong.chatagent.user.model.vo.UserProfileVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry points for persistent user profile summaries.
 */
@RestController
@RequestMapping("/api/user/profile")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public ApiResponse<UserProfileVO> getCurrentUserProfile() {
        return ApiResponse.success(toVO(userProfileService.getCurrentUserProfile()));
    }

    @PutMapping
    public ApiResponse<UserProfileVO> updateCurrentUserProfile(@RequestBody UpdateUserProfileRequest request) {
        return ApiResponse.success(toVO(userProfileService.updateCurrentUserProfile(
                request == null ? null : request.getSummary()
        )));
    }

    private UserProfileVO toVO(UserProfileDTO dto) {
        return UserProfileVO.builder()
                .userId(dto.getUserId())
                .summary(dto.getSummary())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }
}
