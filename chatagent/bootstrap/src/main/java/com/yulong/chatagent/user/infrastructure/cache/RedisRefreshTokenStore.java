package com.yulong.chatagent.user.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import com.yulong.chatagent.user.port.RefreshTokenStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

@Service
@Slf4j
/**
 * Redis-backed storage for refresh tokens.
 *
 * <p>The implementation keeps two indexes:
 * one from token -> user for normal refresh lookups,
 * and one from user -> token hashes for bulk revocation.</p>
 */
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
            // Redis stores a hash of the refresh token instead of the raw value
            // so leaked cache contents do not directly reveal usable tokens.
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
        // This key is the authoritative source used during refresh operations.
        stringRedisTemplate.opsForValue().set(tokenKey, userId, ttl);

        // Keep a reverse index so the application can revoke all refresh tokens
        // for one user without scanning Redis.
        String userTokenSetKey = userTokensKey(userId);
        stringRedisTemplate.opsForSet().add(userTokenSetKey, tokenHash);
        log.info("Refresh token saved in Redis: userId={}, ttlSeconds={}",
                userId,
                ttl.getSeconds());

    }


    @Override
    public String getUserId(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String tokenHash = hashToken(refreshToken);
        String tokenKey = refreshTokenKey(tokenHash);
        String userId = stringRedisTemplate.opsForValue().get(tokenKey);
        log.info("Refresh token lookup completed: found={}", userId != null && !userId.isBlank());
        return userId;
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
            // Remove the reverse index entry as well so later bulk deletions do
            // not keep references to already revoked tokens.
            String userTokenSetKey = userTokensKey(userId);
            stringRedisTemplate.opsForSet().remove(userTokenSetKey, tokenHash);
        }
        log.info("Refresh token deleted: userId={}", userId);
    }


    @Override
    public void deleteByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        // The set stores token hashes; each hash points to a separate token->user key.
        String userTokenSetKey = userTokensKey(userId);
        Set<String> members = stringRedisTemplate.opsForSet().members(userTokenSetKey);

        if (members != null) {
            for (String member : members) {
                String tokenKey = refreshTokenKey(member);
                stringRedisTemplate.delete(tokenKey);
            }
        }

        stringRedisTemplate.delete(userTokenSetKey);
        log.info("All refresh tokens deleted for user: userId={}, tokenCount={}",
                userId,
                members == null ? 0 : members.size());
    }

}
