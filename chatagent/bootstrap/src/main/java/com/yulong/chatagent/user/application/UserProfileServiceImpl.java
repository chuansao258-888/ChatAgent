package com.yulong.chatagent.user.application;

import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.user.model.dto.UserProfileDTO;
import com.yulong.chatagent.user.port.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Default application service for persistent user profile summaries.
 */
@Service
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {
    private static final String DEFAULT_SUMMARY = "No persistent user profile available";

    private final UserProfileRepository userProfileRepository;

    public UserProfileServiceImpl(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Override
    public UserProfileDTO getCurrentUserProfile() {
        LoginUser currentUser = UserContext.requireUser();
        return getUserProfile(currentUser.getUserId());
    }

    @Override
    public UserProfileDTO updateCurrentUserProfile(String summary) {
        LoginUser currentUser = UserContext.requireUser();
        String normalizedSummary = normalizeSummary(summary);
        LocalDateTime now = LocalDateTime.now();

        UserProfileDTO existingProfile = safeFindByUserId(currentUser.getUserId());
        UserProfileDTO profileToSave = UserProfileDTO.builder()
                .userId(currentUser.getUserId())
                .summary(normalizedSummary)
                .createdAt(existingProfile == null ? now : existingProfile.getCreatedAt())
                .updatedAt(now)
                .build();

        try {
            userProfileRepository.saveOrUpdate(profileToSave);
        } catch (RuntimeException ex) {
            log.warn("User profile storage unavailable during update, falling back to in-memory response: userId={}, error={}",
                    currentUser.getUserId(),
                    ex.getMessage());
            return profileToSave;
        }
        return getUserProfile(currentUser.getUserId());
    }

    @Override
    public UserProfileDTO getUserProfile(String userId) {
        UserProfileDTO userProfile = safeFindByUserId(userId);
        if (userProfile != null) {
            return userProfile;
        }
        return UserProfileDTO.builder()
                .userId(userId)
                .summary("")
                .build();
    }

    @Override
    public String getUserProfileSummary(String userId) {
        UserProfileDTO userProfile = safeFindByUserId(userId);
        if (userProfile == null || !StringUtils.hasText(userProfile.getSummary())) {
            return DEFAULT_SUMMARY;
        }
        return userProfile.getSummary();
    }

    private UserProfileDTO safeFindByUserId(String userId) {
        try {
            return userProfileRepository.findByUserId(userId);
        } catch (RuntimeException ex) {
            log.warn("User profile storage unavailable, using default summary: userId={}, error={}",
                    userId,
                    ex.getMessage());
            return null;
        }
    }

    private String normalizeSummary(String summary) {
        return summary == null ? "" : summary.trim();
    }
}
