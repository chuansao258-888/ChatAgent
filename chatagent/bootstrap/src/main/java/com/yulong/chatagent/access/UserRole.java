package com.yulong.chatagent.access;

import org.springframework.util.StringUtils;

/**
 * Supported platform roles.
 *
 * <p>The database and JWT payload may still contain lower-case legacy values,
 * so matching stays case-insensitive and accepts both enum names and stored
 * values during the migration period.</p>
 */
public enum UserRole {

    ADMIN("admin"),
    USER("user");

    private final String persistedValue;

    UserRole(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String persistedValue() {
        return persistedValue;
    }

    public boolean matches(String rawRole) {
        if (!StringUtils.hasText(rawRole)) {
            return false;
        }
        return name().equalsIgnoreCase(rawRole) || persistedValue.equalsIgnoreCase(rawRole);
    }

    public static boolean matchesAny(String rawRole, UserRole[] allowedRoles) {
        if (allowedRoles == null || allowedRoles.length == 0) {
            return true;
        }
        for (UserRole allowedRole : allowedRoles) {
            if (allowedRole != null && allowedRole.matches(rawRole)) {
                return true;
            }
        }
        return false;
    }
}
