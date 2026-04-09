package com.yulong.chatagent.user.model;

import org.springframework.util.StringUtils;

/**
 * Supported lifecycle states for a user account.
 */
public enum UserStatus {

    ACTIVE,
    DISABLED;

    public boolean matches(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return false;
        }
        return name().equalsIgnoreCase(rawStatus.trim());
    }

    public static String normalize(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return ACTIVE.name();
        }
        for (UserStatus status : values()) {
            if (status.matches(rawStatus)) {
                return status.name();
            }
        }
        throw new IllegalArgumentException("Unsupported user status: " + rawStatus);
    }
}
