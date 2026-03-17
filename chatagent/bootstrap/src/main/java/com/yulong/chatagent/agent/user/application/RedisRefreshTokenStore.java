package com.yulong.chatagent.agent.user.application;

import io.jsonwebtoken.lang.Assert;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

@Service
public class RedisRefreshTokenStore implements RefreshTokenStore {
    private final StringRedisTemplate stringRedisTemplate;
    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:refresh:";
    private static final String USER_REFRESH_TOKENS_KEY_PREFIX = "auth:user:";
    private static final String USER_REFRESH_TOKENS_KEY_SUFFIX = ":refresh-tokens";

    public RedisRefreshTokenStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private String hashToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken cannot be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private String refreshTokenKey(String tokenHash) {
        return REFRESH_TOKEN_KEY_PREFIX + tokenHash;
    }

    private String userTokensKey(String userId) {
        return USER_REFRESH_TOKENS_KEY_PREFIX + userId + USER_REFRESH_TOKENS_KEY_SUFFIX;
    }


    @Override
    public void save(String refreshToken, String userId, Duration ttl) {
        Assert.notNull(refreshToken, "refreshToken cannot be null");
        Assert.notNull(userId, "userId cannot be null");
        Assert.notNull(ttl, "ttl cannot be null");
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("ttl must be greater than 0");
        }
        String tokenHash = hashToken(refreshToken);
        String tokenKey = refreshTokenKey(tokenHash);
        stringRedisTemplate.opsForValue().set(tokenKey, userId, ttl);

        String userTokensKey = userTokensKey(userId);
        stringRedisTemplate.opsForSet().add(userTokensKey, tokenHash);
//        stringRedisTemplate.expire(userTokensKey, ttl);

    }


    @Override
    public String getUserId(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String tokenHash = hashToken(refreshToken);
        String tokenKey = refreshTokenKey(tokenHash);
        return stringRedisTemplate.opsForValue().get(tokenKey);
    }


    @Override
    public void delete(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        String userId = getUserId(refreshToken);
        String tokenHash = hashToken(refreshToken);
        String tokenKey = refreshTokenKey(tokenHash);

        stringRedisTemplate.delete(tokenKey);

        if (userId != null && !userId.isBlank()) {
            String userTokenSetKey = userTokensKey(userId);
            stringRedisTemplate.opsForSet().remove(userTokenSetKey, tokenHash);
        }
    }


    @Override
    public void deleteByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        String userTokenSetKey = userTokensKey(userId);
        Set<String> members = stringRedisTemplate.opsForSet().members(userTokenSetKey);

        if (members != null) {
            for (String member : members) {
                String tokenKey = refreshTokenKey(member);
                stringRedisTemplate.delete(tokenKey);
            }
        }

        stringRedisTemplate.delete(userTokenSetKey);
    }

}
