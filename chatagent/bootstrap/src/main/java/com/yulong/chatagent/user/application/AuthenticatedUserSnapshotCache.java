package com.yulong.chatagent.user.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.port.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * Short-lived cache for authenticated user snapshots loaded during request
 * authentication. Admin-side mutations explicitly invalidate entries so
 * permission changes still take effect immediately after commit.
 */
@Component
public class AuthenticatedUserSnapshotCache {

    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(5);
    private static final long MAX_ENTRIES = 10_000L;

    private final UserRepository userRepository;
    private final Cache<String, Optional<UserDTO>> cache;

    public AuthenticatedUserSnapshotCache(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(SNAPSHOT_TTL)
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    public UserDTO getByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        Optional<UserDTO> cachedUser = cache.get(userId, this::loadUser);
        return cachedUser == null ? null : cachedUser.orElse(null);
    }

    public void put(UserDTO user) {
        if (user == null || !StringUtils.hasText(user.getId())) {
            return;
        }
        cache.put(user.getId(), Optional.of(user));
    }

    public void invalidate(String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        cache.invalidate(userId);
    }

    private Optional<UserDTO> loadUser(String userId) {
        return Optional.ofNullable(userRepository.findById(userId));
    }
}
