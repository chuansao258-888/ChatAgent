package com.yulong.chatagent.user.port;

import com.yulong.chatagent.user.model.dto.UserDTO;

import java.util.List;

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

    /**
     * Updates mutable fields on an existing user.
     *
     * @param user user DTO containing the fields to change
     * @return {@code true} when the update succeeded
     */
    boolean update(UserDTO user);

    /**
     * Returns one page of non-deleted users ordered by creation time descending.
     *
     * @param keyword username keyword filter
     * @param status status filter
     * @param limit maximum number of users to return
     * @param offset offset for pagination
     * @return user DTO page
     */
    List<UserDTO> findPage(String keyword, String status, int limit, int offset);

    /**
     * Counts non-deleted users matching the provided filters.
     *
     * @param keyword username keyword filter
     * @param status status filter
     * @return number of matching users
     */
    long count(String keyword, String status);

    /**
     * Locks and returns active administrators for last-admin safety checks.
     *
     * @return active administrator snapshots
     */
    List<UserDTO> findActiveAdminsForUpdate();
}
