package com.yulong.chatagent.agent.user.application;

public interface PasswordService {
    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
