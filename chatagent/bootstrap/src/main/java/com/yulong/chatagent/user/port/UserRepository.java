package com.yulong.chatagent.user.port;

import com.yulong.chatagent.user.model.dto.UserDTO;

/**
 * Persistence contract for user accounts needed by the authentication flow.
 */
public interface UserRepository {

    /**
     * Finds a user by primary key.
     *
     * @param id user identifier
     * @return user DTO, or {@code null} when not found
     */
    UserDTO findById(String id);

    /**
     * Finds a user by unique username.
     *
     * @param username username or login identifier
     * @return user DTO, or {@code null} when not found
     */
    UserDTO findByUsername(String username);

    /**
     * Persists a new user.
     *
     * @param user user DTO to save
     * @return {@code true} when the insert succeeded
     */
    boolean save(UserDTO user);
}
