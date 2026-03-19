package com.yulong.chatagent.user.application;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
/**
 * {@link PasswordService} implementation backed by BCrypt.
 *
 * <p>BCrypt is intentionally slow and salted, which makes brute-force attacks
 * substantially harder than plain hashing approaches.</p>
 */
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
