package com.yulong.chatagent.user.port;

import com.yulong.chatagent.user.model.dto.UserProfileDTO;

/**
 * Persistence port for user profile summaries.
 */
public interface UserProfileRepository {

    /**
     * Loads one profile summary by user identifier.
     *
     * @param userId owner identifier
     * @return matching profile or {@code null}
     */
    UserProfileDTO findByUserId(String userId);

    /**
     * Inserts or updates one profile summary.
     *
     * @param userProfile profile to persist
     * @return {@code true} on success
     */
    boolean saveOrUpdate(UserProfileDTO userProfile);
}
