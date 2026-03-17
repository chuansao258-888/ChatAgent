package com.yulong.chatagent.context;

import com.yulong.chatagent.exception.BizException;

public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static LoginUser requireUser() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException("User not authenticated");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
