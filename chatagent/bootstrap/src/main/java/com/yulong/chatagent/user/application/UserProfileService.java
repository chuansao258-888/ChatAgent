package com.yulong.chatagent.user.application;

import com.yulong.chatagent.user.model.dto.UserProfileDTO;

/**
 * Application service for user profile summaries.
 */
public interface UserProfileService {

    /**
     * Returns the current authenticated user's profile summary.
     *
     * @return current profile, creating an empty in-memory view when missing
     */
    UserProfileDTO getCurrentUserProfile();

    /**
     * Creates or updates the current authenticated user's profile summary.
     *
     * @param summary new summary text, blank to clear
     * @return persisted profile
     */
    UserProfileDTO updateCurrentUserProfile(String summary);

    /**
     * Loads one profile by owner identifier.
     *
     * @param userId owner identifier
     * @return matching profile or a synthetic empty profile when absent
     */
    UserProfileDTO getUserProfile(String userId);

    /**
     * Returns the stored summary text for one user.
     *
     * @param userId owner identifier
     * @return stored summary, or a default sentence when absent
     */
    String getUserProfileSummary(String userId);
}
