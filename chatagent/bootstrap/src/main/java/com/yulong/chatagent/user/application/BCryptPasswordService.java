package com.yulong.chatagent.user.application;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * BCrypt-based implementation of password hashing and verification; credential checks fail closed
 * (return {@code false}) on null inputs rather than throwing.
 */
@Service
public class BCryptPasswordService implements PasswordService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("rawPassword cannot be blank");
        }
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        // Invalid credential checks should fail closed and simply return false.
        if (rawPassword == null || passwordHash == null) return false;
        return passwordEncoder.matches(rawPassword, passwordHash);
    }
}
