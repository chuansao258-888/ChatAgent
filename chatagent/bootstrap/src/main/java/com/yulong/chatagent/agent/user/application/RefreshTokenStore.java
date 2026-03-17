package com.yulong.chatagent.agent.user.application;

import java.time.Duration;

public interface RefreshTokenStore {

    void save(String refreshToken, String userId, Duration ttl);

    String getUserId(String refreshToken);

    void delete(String refreshToken);

    void deleteByUserId(String userId);
}
