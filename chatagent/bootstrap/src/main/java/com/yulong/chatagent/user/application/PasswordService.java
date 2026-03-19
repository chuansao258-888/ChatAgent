package com.yulong.chatagent.user.application;

/**
 * Hashes passwords for persistence and verifies raw passwords during login.
 */
public interface PasswordService {

    /**
     * Produces a secure hash for a raw user password.
     *
     * @param rawPassword raw password supplied by the user
     * @return encoded password hash suitable for storage
     */
    String hash(String rawPassword);

    /**
     * Checks whether a raw password matches a previously stored hash.
     *
     * @param rawPassword raw password supplied by the user
     * @param passwordHash encoded password hash stored in persistence
     * @return {@code true} when the password is valid; {@code false} otherwise
     */
    boolean matches(String rawPassword, String passwordHash);
}
