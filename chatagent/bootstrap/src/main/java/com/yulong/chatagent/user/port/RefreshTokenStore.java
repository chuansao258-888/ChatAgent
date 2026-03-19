package com.yulong.chatagent.user.port;

import java.time.Duration;

/**
 * Abstraction for storing and revoking refresh tokens.
 *
 * <p>The implementation is free to use Redis or any other backing store as
 * long as it can look up a user by refresh token and revoke sessions.</p>
 */
public interface RefreshTokenStore {

    /**
     * Stores a refresh token for a user with a fixed time-to-live.
     *
     * @param refreshToken opaque refresh token
     * @param userId owner of the token
     * @param ttl lifetime of the token
     */
    void save(String refreshToken, String userId, Duration ttl);

    /**
     * Resolves the user ID associated with a refresh token.
     *
     * @param refreshToken opaque refresh token
     * @return owning user ID, or {@code null} when the token is missing or expired
     */
    String getUserId(String refreshToken);

    /**
     * Revokes a single refresh token.
     *
     * @param refreshToken token to revoke
     */
    void delete(String refreshToken);

    /**
     * Revokes all refresh tokens currently associated with a user.
     *
     * @param userId user whose sessions should be revoked
     */
    void deleteByUserId(String userId);
}
