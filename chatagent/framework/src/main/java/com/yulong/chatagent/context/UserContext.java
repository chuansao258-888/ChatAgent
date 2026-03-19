package com.yulong.chatagent.context;

import com.yulong.chatagent.exception.BizException;

/**
 * Thread-local storage for the current authenticated user.
 *
 * <p>Authentication middleware populates this context after successful token
 * validation and clears it when request processing ends.</p>
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * Stores the current authenticated user for the active thread.
     *
     * @param user authenticated user snapshot
     */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * Returns the current authenticated user, or {@code null} when the request
     * is unauthenticated.
     *
     * @return current user or {@code null}
     */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * Returns the current authenticated user or throws when authentication is missing.
     *
     * @return current authenticated user
     * @throws BizException when no authenticated user is present
     */
    public static LoginUser requireUser() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException("User not authenticated");
        }
        return user;
    }

    /**
     * Clears the current user from the active thread.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
